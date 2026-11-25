const functions = require('firebase-functions/v1');
const { randomBytes, scryptSync, timingSafeEqual } = require('node:crypto');
const { initializeApp } = require('firebase-admin/app');
const { FieldPath, FieldValue, Timestamp, getFirestore } = require('firebase-admin/firestore');
const { getStorage } = require('firebase-admin/storage');
const { getMessaging } = require('firebase-admin/messaging');
const { DAY_MILLIS, todayInAthens, birthdayAt } = require('./dates');

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

// A deleted document takes its file and its share links along, also when it was deleted offline.
// The app never deletes from Storage itself, so the Storage rules allow no deletes at all.
exports.cleanUpDocument = europe.firestore
  .document('spaces/{spaceId}/documents/{documentId}')
  .onDelete(async (snapshot, context) => {
    const { spaceId, documentId } = context.params;
    await getStorage().bucket().deleteFiles({ prefix: `spaces/${spaceId}/documents/${documentId}/` });
    const shares = await getFirestore().collection('shares').where('spaceId', '==', spaceId).where('documentId', '==', documentId).get();
    await Promise.all(shares.docs.map((share) => share.ref.delete()));
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
    const today = todayInAthens();
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

// How old a child is when they become an adult. ADULT_AGE in the app says the same.
const ADULT_AGE = 18;

// Every night, a child whose 18th birthday is today, or went by while this did not run, becomes an
// adult. Their documents lock at the same time, so the new adult chooses what the parents keep
// seeing by unlocking them. They are told, and so is the rest of the space.
exports.comeOfAge = europe.pubsub
  .schedule('every day 00:10')
  .timeZone('Europe/Athens')
  .onRun(async () => {
    const db = getFirestore();
    const today = todayInAthens();
    // shortcut: reads every space each night, which is fine for a few hundred families; keep the
    // next birthdays in a collection of their own once there are many more.
    const spaces = await db.collection('spaces').get();
    for (const space of spaces.docs) {
      const members = space.get('members') || {};
      for (const [id, member] of Object.entries(members)) {
        if (member.role === 'adult' || typeof member.birthDay !== 'number' || birthdayAt(member.birthDay, ADULT_AGE) > today) continue;
        const theirs = await space.ref.collection('documents').where('ownerId', '==', id).get();
        await Promise.all(theirs.docs.map((document) => document.ref.update({ locked: true })));
        await db.batch()
          .update(space.ref, new FieldPath('members', id, 'role'), 'adult')
          .create(space.ref.collection('activity').doc(), {
            at: FieldValue.serverTimestamp(),
            actorId: id,
            actorName: member.name,
            action: 'grew_up',
            memberId: id,
            memberName: member.name,
          })
          .commit();
        const others = Object.keys(members).filter((other) => other !== id);
        await notify(db, [id], {}, { titleLocKey: 'push_grew_up_you_title', bodyLocKey: 'push_grew_up_you', channelId: 'reminders', tag: `adult-${space.id}` });
        await notify(db, others, {}, { title: member.name, bodyLocKey: 'push_grew_up', channelId: 'reminders', tag: `adult-${space.id}-${id}` });
      }
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

// Emergency access. A person names, on their profile, an adult they share a space with and a wait
// (users/{uid}.emergency, written by the app; the rules check its shape). The contact asks here,
// which starts the wait and tells the person, who can take the request back in the app. Once the
// wait is over the contact opens the person's locked documents in every shared space, for
// UNLOCK_MINUTES at a time like after a PIN, and the person is told each time.

/** The spaces where `contactId` is an adult and `ownerId` a member. */
async function sharedSpaces(db, contactId, ownerId) {
  const mine = await db.collection('spaces').where('memberIds', 'array-contains', contactId).get();
  return mine.docs.filter((space) => {
    const members = space.get('members') || {};
    return members[contactId]?.role === 'adult' && members[ownerId];
  });
}

/** The caller as the emergency contact of `ownerId`, or an error. */
async function emergencyOf(db, context, ownerId) {
  const auth = requireAuth(context);
  if (typeof ownerId !== 'string') throw new HttpsError('invalid-argument', 'ownerId is needed.');
  const user = await db.doc(`users/${ownerId}`).get();
  const emergency = user.get('emergency');
  if (!emergency || emergency.contactId !== auth.uid) throw new HttpsError('permission-denied', 'Not their emergency contact.');
  const spaces = await sharedSpaces(db, auth.uid, ownerId);
  if (spaces.length === 0) throw new HttpsError('permission-denied', 'No shared space where you are an adult.');
  return { auth, user, emergency, spaces, me: spaces[0].get('members')[auth.uid] };
}

exports.askEmergency = europe.https.onCall(async (data, context) => {
  const db = getFirestore();
  const { auth, user, emergency, me } = await emergencyOf(db, context, data?.ownerId);
  if (emergency.askedAt) return { result: 'ok' };
  await user.ref.update({ 'emergency.askedAt': FieldValue.serverTimestamp() });
  await notify(db, [user.id], {}, { title: me.name, bodyLocKey: 'push_emergency_asked', channelId: 'reminders', tag: `emergency-${auth.uid}` });
  return { result: 'ok' };
});

exports.openEmergency = europe.https.onCall(async (data, context) => {
  const db = getFirestore();
  const { auth, user, emergency, spaces, me } = await emergencyOf(db, context, data?.ownerId);
  // Written so that a missing or odd value fails: NaN compares false.
  if (!(emergency.askedAt && emergency.askedAt.toMillis() + emergency.waitDays * DAY_MILLIS <= Date.now())) {
    throw new HttpsError('failed-precondition', 'The wait is not over.');
  }
  const until = Timestamp.fromMillis(Date.now() + UNLOCK_MINUTES * 60_000);
  for (const space of spaces) {
    await db.doc(`spaces/${space.id}/unlocks/${auth.uid}`).set({ grants: { [user.id]: until } }, { merge: true });
    await space.ref.collection('activity').add({
      at: FieldValue.serverTimestamp(),
      actorId: auth.uid,
      actorName: me.name,
      action: 'emergency',
      memberId: user.id,
      memberName: space.get('members')[user.id].name,
    });
  }
  await notify(db, [user.id], {}, { title: me.name, bodyLocKey: 'push_emergency_opened', channelId: 'reminders', tag: `emergency-${auth.uid}` });
  return { result: 'ok', untilMillis: until.toMillis() };
});

// Share links. One opens the file of one document for anyone who has it, no sign-in needed, until
// it runs out. shares/{shareId} is written here only. The file in Storage gets a download token of
// its own for each link (the address the link redirects to), taken back when the link ends.
const SHARE_HOURS = [1, 24, 168];
const SHARE_ID = /^[\w-]{22}$/;
const fileOf = (spaceId, documentId) => getStorage().bucket().file(`spaces/${spaceId}/documents/${documentId}/file`);
const GONE_PAGE = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Vault Anything</title><style>body{font-family:sans-serif;margin:3rem auto;max-width:30rem;padding:0 1rem;line-height:1.5}</style></head>
<body><h1>Vault Anything</h1><p>This link has run out. Ask the person who sent it for a new one.</p>
<p lang="el">Αυτός ο σύνδεσμος έληξε. Ζήτα έναν νέο από όποιον τον έστειλε.</p></body></html>`;

/** Rewrites the file's download tokens with [change], and names the file for the browser. */
async function setTokens(file, fileName, change) {
  const [metadata] = await file.getMetadata();
  const tokens = change((metadata.metadata?.firebaseStorageDownloadTokens || '').split(',').filter(Boolean));
  await file.setMetadata({
    contentDisposition: `inline; filename*=UTF-8''${encodeURIComponent(fileName || 'file')}`,
    metadata: { firebaseStorageDownloadTokens: tokens.length ? tokens.join(',') : null },
  });
}

/** Ends a share: the link stops, and the file's token with it. A file already gone is fine. */
async function endShare(share) {
  const { spaceId, documentId, fileName, token } = share.data();
  try {
    await setTokens(fileOf(spaceId, documentId), fileName, (tokens) => tokens.filter((t) => t !== token));
  } catch (e) {
    if (e.code !== 404) throw e;
  }
  await share.ref.delete();
}

/** The space's members and the caller's entry, or permission-denied. */
async function memberOf(db, spaceId, uid) {
  const space = await db.doc(`spaces/${spaceId}`).get();
  const members = (space.exists && space.get('members')) || {};
  if (!members[uid]) throw new HttpsError('permission-denied', 'Not a member of this space.');
  return { members, me: members[uid] };
}

// Makes a link to the file of {spaceId, documentId} that works for {hours} (one of SHARE_HOURS): by
// the owner, or by an adult for a child's document (mayManage in the rules), a locked one by its
// owner alone. The adults see it in the activity log.
exports.shareDocument = europe.https.onCall(async (data, context) => {
  const auth = requireAuth(context);
  const db = getFirestore();
  const { spaceId, documentId, hours } = data || {};
  if (typeof spaceId !== 'string' || typeof documentId !== 'string' || !SHARE_HOURS.includes(hours)) {
    throw new HttpsError('invalid-argument', 'spaceId, documentId and hours (1, 24 or 168) are needed.');
  }
  const { members, me } = await memberOf(db, spaceId, auth.uid);
  const document = await db.doc(`spaces/${spaceId}/documents/${documentId}`).get();
  if (!document.exists) throw new HttpsError('not-found', 'No such document.');
  const ownerId = document.get('ownerId');
  const mine = ownerId === auth.uid;
  if (!mine && (me.role !== 'adult' || members[ownerId]?.role === 'adult' || document.get('locked') === true)) {
    throw new HttpsError('permission-denied', 'Only the owner, or an adult for a child, shares a document.');
  }
  if (document.get('kind') === 'link' || document.get('uploaded') === false) {
    throw new HttpsError('failed-precondition', 'Only an uploaded file is shared by link.');
  }
  const id = randomBytes(16).toString('base64url');
  const token = randomBytes(16).toString('hex');
  const fileName = document.get('fileName');
  await setTokens(fileOf(spaceId, documentId), fileName, (tokens) => [...tokens, token]);
  const expiresAt = Timestamp.fromMillis(Date.now() + hours * 3_600_000);
  const url = `https://${process.env.GCLOUD_PROJECT}.web.app/s/${id}`;
  const batch = db.batch();
  batch.set(db.doc(`shares/${id}`), {
    spaceId,
    documentId,
    ownerId,
    createdById: auth.uid,
    createdByName: me.name,
    title: document.get('title'),
    fileName,
    token,
    url,
    expiresAt,
    createdAt: FieldValue.serverTimestamp(),
  });
  batch.set(db.collection(`spaces/${spaceId}/activity`).doc(), {
    at: FieldValue.serverTimestamp(),
    actorId: auth.uid,
    actorName: me.name,
    action: 'shared',
    documentId,
    documentTitle: document.get('title'),
  });
  await batch.commit();
  return { id, url, expiresAtMillis: expiresAt.toMillis() };
});

// Stops the link {shareId} before its time: whoever made it, the owner of the document, or an adult
// looking after a child's.
exports.unshareDocument = europe.https.onCall(async (data, context) => {
  const auth = requireAuth(context);
  const db = getFirestore();
  const { shareId } = data || {};
  if (typeof shareId !== 'string' || !SHARE_ID.test(shareId)) throw new HttpsError('invalid-argument', 'shareId is needed.');
  const share = await db.doc(`shares/${shareId}`).get();
  if (!share.exists) return { result: 'ok' };
  const { spaceId, ownerId, createdById } = share.data();
  const { members, me } = await memberOf(db, spaceId, auth.uid);
  const mayStop = createdById === auth.uid || ownerId === auth.uid || (me.role === 'adult' && members[ownerId]?.role !== 'adult');
  if (!mayStop) throw new HttpsError('permission-denied', 'Not your link to stop.');
  await endShare(share);
  return { result: 'ok' };
});

// Every hour, the links that ran out stop for good: until then the file's own address, which the link
// redirected to, would still open for someone who kept it.
exports.expireShares = europe.pubsub.schedule('every 1 hours').onRun(async () => {
  const due = await getFirestore().collection('shares').where('expiresAt', '<=', Timestamp.now()).get();
  for (const share of due.docs) await endShare(share);
});

// /s/{shareId} on the app's site (firebase.json sends it here): the file behind a share link, for
// anyone who has the link, until it runs out.
exports.share = europe.https.onRequest(async (req, res) => {
  const id = req.path.split('/').filter(Boolean).pop() || '';
  const share = SHARE_ID.test(id) ? await getFirestore().doc(`shares/${id}`).get() : null;
  res.set('Cache-Control', 'private, no-store');
  if (!share?.exists || share.get('expiresAt').toMillis() <= Date.now()) {
    res.status(410).type('html').send(GONE_PAGE);
    return;
  }
  const { spaceId, documentId, token } = share.data();
  const file = fileOf(spaceId, documentId);
  res.redirect(302, `https://firebasestorage.googleapis.com/v0/b/${file.bucket.name}/o/${encodeURIComponent(file.name)}?alt=media&token=${token}`);
});
