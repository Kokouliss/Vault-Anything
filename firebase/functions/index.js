const functions = require('firebase-functions/v1');
const { initializeApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { getStorage } = require('firebase-admin/storage');

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
