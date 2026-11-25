const functions = require('firebase-functions/v1');
const { randomBytes, scryptSync, timingSafeEqual } = require('node:crypto');
const { initializeApp } = require('firebase-admin/app');
const { FieldValue, Timestamp, getFirestore } = require('firebase-admin/firestore');
const { getStorage } = require('firebase-admin/storage');
const { getMessaging } = require('firebase-admin/messaging');

initializeApp();

// First generation functions on purpose: they may run in any region, wherever the database is.
const europe = functions.region('europe-west1');

// A deleted space takes everything under it along: its documents and their files. The app only
// deletes the space itself, so this also covers a phone that goes offline halfway.
exports.cleanUpSpace = europe
  .runWith({ timeoutSeconds: 540 })
  .firestore.document('spaces/{spaceId}')
  .onDelete(async (snapshot, context) => {
    await getFirestore().recursiveDelete(snapshot.ref);
    await getStorage().bucket().deleteFiles({ prefix: `spaces/${context.params.spaceId}/` });
  });

// A deleted document takes its file along, also when it was deleted offline. The app never deletes
// from Storage itself, so the Storage rules allow no deletes at all.
exports.cleanUpDocument = europe.firestore
  .document('spaces/{spaceId}/documents/{documentId}')
  .onDelete((snapshot, context) => {
    const { spaceId, documentId } = context.params;
    return getStorage().bucket().deleteFiles({ prefix: `spaces/${spaceId}/documents/${documentId}/` });
  });

// How many days before a document runs out the family hears about it, and once more on the day.
const REMINDER_DAYS = [30, 7, 0];

// Every morning, one notification per document that runs out in 30 days, in 7 days or today, to
// the people who may see it: its owner and the adults of the space. A tap opens the document.
exports.remindExpiring = europe.pubsub
  .schedule('every day 09:00')
  .timeZone('Europe/Athens')
  .onRun(async () => {
    const db = getFirestore();
    // Days since 1970 of today's date in Greece, the count the app keeps in expiresDay.
    const today = Math.floor(Date.parse(new Date().toLocaleDateString('en-CA', { timeZone: 'Europe/Athens' })) / 86_400_000);
    const due = await db
      .collectionGroup('documents')
      .where('expiresDay', 'in', REMINDER_DAYS.map((days) => today + days))
      .get();
    for (const document of due.docs) {
      const spaceRef = document.ref.parent.parent;
      const space = await spaceRef.get();
      if (!space.exists) continue;
      const members = space.get('members') || {};
      const ownerId = document.get('ownerId');
      const people = Object.keys(members).filter((id) => id === ownerId || members[id].role === 'adult');
      const daysLeft = document.get('expiresDay') - today;
      await notify(db, people, { spaceId: spaceRef.id, documentId: document.id }, {
        title: document.get('title'),
        bodyLocKey: daysLeft === 0 ? 'push_expires_today' : daysLeft === 7 ? 'push_expires_week' : 'push_expires_month',
        channelId: 'reminders',
        tag: document.id,
      });
    }
  });

/** One notification to every device of [people]. A tap opens the document that [data] names. */
async function notify(db, people, data, notification) {
  const devices = (await Promise.all(people.map((id) => db.collection(`users/${id}/tokens`).get()))).flatMap(
    (snapshot) => snapshot.docs,
  );
  if (devices.length === 0) return;

  const result = await getMessaging().sendEachForMulticast({
    tokens: devices.map((device) => device.id),
    data,
    android: { notification },
  });

  // Firebase no longer knows the address of an app that was removed, so forget it too.
  await Promise.all(
    result.responses.map((response, i) =>
      response.error?.code === 'messaging/registration-token-not-registered' ? devices[i].ref.delete() : null,
    ),
  );
}

// Someone who leaves, or is removed, takes their documents with them: the family keeps nothing of a
// person who is gone. Each deleted document fires cleanUpDocument for its file.
exports.cleanUpLeaver = europe.firestore.document('spaces/{spaceId}').onUpdate(async (change) => {
  const after = new Set(change.after.get('memberIds') || []);
  const gone = (change.before.get('memberIds') || []).filter((id) => !after.has(id));
  for (const ownerId of gone) {
    const theirs = await change.after.ref.collection('documents').where('ownerId', '==', ownerId).get();
    await Promise.all(theirs.docs.map((document) => document.ref.delete()));
  }
});

// Someone whose account is gone takes their PIN and their notification addresses along.
exports.cleanUpUser = europe.auth.user().onDelete((user) => {
  const db = getFirestore();
  return db.recursiveDelete(db.doc(`users/${user.uid}`));
});

// PINs. The PIN itself never reaches a phone: users/{uid}/private/lock holds a salted hash, out of
// reach of the security rules, and the three callables below are the only way to it. A wrong PIN
// is counted; after MAX_TRIES the PIN is refused for LOCKOUT_MINUTES.
const PIN = /^\d{4,8}$/;
const MAX_TRIES = 5;
const LOCKOUT_MINUTES = 15;
// How long an adult sees the locked documents of someone else after giving their PIN.
const UNLOCK_MINUTES = 15;
// A sign-in this recent stands in for the current PIN when it was forgotten (the app asks for the
// password or the Google account again, which refreshes the sign-in time).
const FRESH_SIGN_IN_SECONDS = 5 * 60;
const { HttpsError } = functions.https;

const hashPin = (pin, salt) => scryptSync(pin, salt, 32);
const lockOf = (db, uid) => db.doc(`users/${uid}/private/lock`);

function requireAuth(context) {
  if (!context.auth) throw new HttpsError('unauthenticated', 'Sign in first.');
  return context.auth;
}

/** 'ok', 'wrong', 'tooManyTries' or 'noPin'. A wrong try is counted; the fifth locks the PIN for a while. */
async function checkPin(db, uid, pin) {
  const ref = lockOf(db, uid);
  const lock = await ref.get();
  if (!lock.exists) return 'noPin';
  const { hash, salt, failures = 0, lockedUntil = null } = lock.data();
  if (lockedUntil && lockedUntil.toMillis() > Date.now()) return 'tooManyTries';
  if (PIN.test(pin) && timingSafeEqual(Buffer.from(hash, 'hex'), hashPin(pin, salt))) {
    if (failures || lockedUntil) await ref.update({ failures: 0, lockedUntil: null });
    return 'ok';
  }
  const tries = failures + 1;
  if (tries >= MAX_TRIES) {
    await ref.update({ failures: 0, lockedUntil: Timestamp.fromMillis(Date.now() + LOCKOUT_MINUTES * 60_000) });
    return 'tooManyTries';
  }
  await ref.update({ failures: tries });
  return 'wrong';
}

async function writePin(db, uid, pin) {
  const salt = randomBytes(16).toString('hex');
  const batch = db.batch();
  batch.set(lockOf(db, uid), { hash: hashPin(pin, salt).toString('hex'), salt, failures: 0, lockedUntil: null, updatedAt: FieldValue.serverTimestamp() });
  batch.set(db.doc(`users/${uid}`), { hasPin: true }, { merge: true });
  await batch.commit();
}

// Sets the caller's PIN ({pin}), changes it ({pin, current}) or removes it ({pin: null, current}).
// With a PIN in place the current one is needed, or a sign-in from the last few minutes.
exports.setPin = europe.https.onCall(async (data, context) => {
  const auth = requireAuth(context);
  const db = getFirestore();
  const { pin = null, current = null } = data || {};
  if (pin !== null && !PIN.test(String(pin))) throw new HttpsError('invalid-argument', 'A PIN is 4 to 8 digits.');
  const existing = await lockOf(db, auth.uid).get();
  if (existing.exists && auth.token.auth_time * 1000 < Date.now() - FRESH_SIGN_IN_SECONDS * 1000) {
    if (current === null) return { result: 'needsSignIn' };
    const result = await checkPin(db, auth.uid, String(current));
    if (result !== 'ok') return { result };
  }
  if (pin === null) {
    const batch = db.batch();
    batch.delete(lockOf(db, auth.uid));
    batch.set(db.doc(`users/${auth.uid}`), { hasPin: false }, { merge: true });
    await batch.commit();
  } else {
    await writePin(db, auth.uid, String(pin));
  }
  return { result: 'ok' };
});

// Checks {ownerId}'s PIN for a member of {spaceId}: their own, or as an adult someone else's. A right
// PIN for someone else's opens their locked documents in that space for UNLOCK_MINUTES, through
// spaces/{spaceId}/unlocks/{caller}, which the security rules read.
exports.verifyPin = europe.https.onCall(async (data, context) => {
  const auth = requireAuth(context);
  const db = getFirestore();
  const { spaceId, ownerId, pin } = data || {};
  if (typeof spaceId !== 'string' || typeof ownerId !== 'string') throw new HttpsError('invalid-argument', 'spaceId and ownerId are needed.');
  const space = await db.doc(`spaces/${spaceId}`).get();
  const members = (space.exists && space.get('members')) || {};
  const me = members[auth.uid];
  if (!me) throw new HttpsError('permission-denied', 'Not a member of this space.');
  if (ownerId !== auth.uid && (me.role !== 'adult' || !members[ownerId])) {
    throw new HttpsError('permission-denied', 'Only an adult unlocks the documents of another member.');
  }
  const result = await checkPin(db, ownerId, String(pin ?? ''));
  if (result !== 'ok' || ownerId === auth.uid) return { result };
  const until = Timestamp.fromMillis(Date.now() + UNLOCK_MINUTES * 60_000);
  await db.doc(`spaces/${spaceId}/unlocks/${auth.uid}`).set({ grants: { [ownerId]: until } }, { merge: true });
  return { result, untilMillis: until.toMillis() };
});

// An adult gives {memberId}, a child of {spaceId}, a new PIN, for a child who forgot theirs. The
// other adults see it in the activity log.
exports.resetPin = europe.https.onCall(async (data, context) => {
  const auth = requireAuth(context);
  const db = getFirestore();
  const { spaceId, memberId, pin } = data || {};
  if (typeof spaceId !== 'string' || typeof memberId !== 'string' || !PIN.test(String(pin))) {
    throw new HttpsError('invalid-argument', 'spaceId, memberId and a PIN of 4 to 8 digits are needed.');
  }
  const space = await db.doc(`spaces/${spaceId}`).get();
  const members = (space.exists && space.get('members')) || {};
  const me = members[auth.uid];
  const them = members[memberId];
  if (!me || me.role !== 'adult' || !them || them.role === 'adult') {
    throw new HttpsError('permission-denied', 'Only an adult resets the PIN of a child in the same space.');
  }
  await writePin(db, memberId, String(pin));
  await db.collection(`spaces/${spaceId}/activity`).add({
    at: FieldValue.serverTimestamp(),
    actorId: auth.uid,
    actorName: me.name,
    action: 'pin_reset',
    memberId,
    memberName: them.name,
  });
  return { result: 'ok' };
});
