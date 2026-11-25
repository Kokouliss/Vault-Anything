// Runs storage.rules on the local emulators, after rules.test.mjs: both use the same emulator data, so
// the test script runs one file at a time. The Storage rules look the document up in Firestore,
// so both emulators run (see the test script in package.json).
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, test } from 'node:test';
import { assertFails, assertSucceeds, initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, setDoc } from 'firebase/firestore';
import { deleteObject, getBytes, ref, uploadBytes } from 'firebase/storage';

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-vault-anything',
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
    storage: { rules: readFileSync(new URL('../storage.rules', import.meta.url), 'utf8') },
  });
});

after(() => env.cleanup());

const path = (documentId) => `spaces/s1/documents/${documentId}/file`;

// The same family as in rules.test.mjs, with the kid's record written and its file not uploaded yet.
// clearStorage() only sees files at the root of the bucket, so the nested files are removed by name.
const removeFile = (context, documentId) =>
  deleteObject(ref(context.storage(), path(documentId))).catch((e) => {
    if (e.code !== 'storage/object-not-found') throw e;
  });

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (context) => {
    await Promise.all(['kids', 'members', 'owners'].map((id) => removeFile(context, id)));
    const db = context.firestore();
    await setDoc(doc(db, 'spaces/s1'), {
      name: 'Family',
      ownerId: 'owner',
      memberIds: ['owner', 'member', 'kid'],
      members: { owner: { name: 'Owner', role: 'adult' }, member: { name: 'Member', role: 'adult' }, kid: { name: 'Kid', role: 'child' } },
      inviteCode: 'ABCD2345',
      invitedEmails: [],
      createdAt: new Date(),
    });
    const record = (ownerId) => ({ ownerId, title: 'Scan', kind: 'file', category: 'other', tags: [], favorite: false, fileName: 'scan.pdf', mimeType: 'application/pdf', sizeBytes: 3, uploaded: false, addedById: ownerId, addedByName: 'Someone', createdAt: new Date(), updatedAt: new Date() });
    await setDoc(doc(db, 'spaces/s1/documents/kids'), record('kid'));
    await setDoc(doc(db, 'spaces/s1/documents/members'), record('member'));
    await setDoc(doc(db, 'spaces/s1/documents/owners'), record('owner'));
    await uploadBytes(ref(context.storage(), path('owners')), new Uint8Array(3));
    await uploadBytes(ref(context.storage(), path('members')), new Uint8Array(3));
  });
});

const storage = (uid) => env.authenticatedContext(uid).storage();
const upload = (uid, documentId) => uploadBytes(ref(storage(uid), path(documentId)), new Uint8Array(3));

test('a file is uploaded by its owner or by an adult for a child, and only once its record exists', async () => {
  await assertFails(upload('kid', 'nobodys'));
  await assertFails(upload('stranger', 'kids'));
  await assertFails(upload('member', 'owners'));
  await assertSucceeds(upload('kid', 'kids'));
  // Written once: the record is the one that says what the file is.
  await assertFails(upload('owner', 'kids'));
});

test('an adult uploads for a child', async () => {
  await assertSucceeds(upload('owner', 'kids'));
});

test("adults download everyone's files, children only their own", async () => {
  const download = (uid, documentId) => getBytes(ref(storage(uid), path(documentId)));
  await assertSucceeds(download('owner', 'members'));
  await assertSucceeds(download('member', 'owners'));
  await assertFails(download('kid', 'owners'));
  await assertFails(download('stranger', 'owners'));
});

test('nobody deletes a file by hand', async () => {
  await assertFails(deleteObject(ref(storage('owner'), path('owners'))));
});
