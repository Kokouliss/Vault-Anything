const functions = require('firebase-functions/v1');
const { initializeApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
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
