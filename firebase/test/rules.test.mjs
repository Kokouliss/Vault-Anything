// Runs firestore.rules on the local emulator. Every write below has the same shape as the one
// the Android app sends (see core/data/.../firebase), so a rule that would break the app fails here.
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, test } from 'node:test';
import { assertFails, assertSucceeds, initializeTestEnvironment } from '@firebase/rules-unit-testing';
import {
  Bytes,
  arrayRemove,
  arrayUnion,
  collection,
  deleteDoc,
  deleteField,
  doc,
  getDoc,
  getDocs,
  query,
  serverTimestamp,
  setDoc,
  updateDoc,
  where,
  writeBatch,
} from 'firebase/firestore';

const CODE = 'ABCD2345';
const NEW_CODE = 'WXYZ6789';
const SPACE = 'spaces/s1';
const DOCUMENTS = `${SPACE}/documents`;
const OWNERS_PASSPORT = `${DOCUMENTS}/d1`;
const KIDS_REPORT = `${DOCUMENTS}/d2`;
const MEMBERS_TEST = `${DOCUMENTS}/d3`;

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-vault-anything',
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
  });
});

after(() => env.cleanup());

// A space owned by "owner" (adult) with another adult "member" and a child "kid", one pending email
// invite, and one document of each person.
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(doc(db, SPACE), {
      name: 'Family',
      ownerId: 'owner',
      memberIds: ['owner', 'member', 'kid'],
      members: { owner: { name: 'Owner', role: 'adult' }, member: { name: 'Member', role: 'adult' }, kid: { name: 'Kid', role: 'child' } },
      inviteCode: CODE,
      invitedEmails: ['guest@example.com'],
      createdAt: new Date(),
    });
    await setDoc(doc(db, `invites/${CODE}`), { spaceId: 's1' });
    await setDoc(doc(db, OWNERS_PASSPORT), stored('owner', 'Passport'));
    await setDoc(doc(db, KIDS_REPORT), stored('kid', 'School report'));
    await setDoc(doc(db, MEMBERS_TEST), stored('member', 'Blood test'));
  });
});

/** A file document as the server holds it. */
function stored(ownerId, title) {
  return {
    ownerId,
    title,
    kind: 'file',
    category: 'other',
    tags: [],
    favorite: false,
    expiresDay: null,
    note: null,
    fileName: 'scan.pdf',
    mimeType: 'application/pdf',
    sizeBytes: 1000,
    uploaded: true,
    addedById: ownerId,
    addedByName: 'Someone',
    createdAt: new Date(),
    updatedAt: new Date(),
  };
}

/** A new file document as the app writes it. */
function newFile(ownerId, addedById, extra = {}) {
  return {
    ownerId,
    title: 'Insurance',
    kind: 'file',
    category: 'car',
    tags: ['car', 'insurance'],
    favorite: true,
    expiresDay: 20800,
    note: 'Renew in June',
    fileName: 'insurance.pdf',
    mimeType: 'application/pdf',
    sizeBytes: 2000,
    uploaded: false,
    thumbnail: Bytes.fromUint8Array(new Uint8Array(10)),
    addedById,
    addedByName: 'Someone',
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    ...extra,
  };
}

function newLink(ownerId, addedById, link = 'https://gov.gr/passport') {
  const { fileName, mimeType, sizeBytes, uploaded, thumbnail, ...rest } = newFile(ownerId, addedById);
  return { ...rest, kind: 'link', link };
}

function as(uid, email, emailVerified = true) {
  const token = email ? { email, email_verified: emailVerified } : {};
  return env.authenticatedContext(uid, token).firestore();
}
const owner = () => as('owner', 'owner@example.com');
const member = () => as('member', 'member@example.com');
const kid = () => as('kid', 'kid@example.com');
const stranger = () => as('stranger', 'stranger@example.com');
const guest = () => as('guest', 'guest@example.com');

function joinWithCode(db, uid, code, role = 'child') {
  return updateDoc(doc(db, SPACE), {
    memberIds: arrayUnion(uid),
    [`members.${uid}`]: { name: 'New', role, inviteCode: code },
  });
}

function resetCode(db, extra = {}) {
  return writeBatch(db)
    .update(doc(db, SPACE), { inviteCode: NEW_CODE, ...extra })
    .delete(doc(db, `invites/${CODE}`))
    .set(doc(db, `invites/${NEW_CODE}`), { spaceId: 's1' })
    .commit();
}

function newSpace(db, ownerId, { withInvite = true, role = 'adult' } = {}) {
  const batch = writeBatch(db).set(doc(db, 'spaces/s2'), {
    name: 'Grandparents',
    ownerId,
    memberIds: [ownerId],
    members: { [ownerId]: { name: 'Me', role } },
    inviteCode: NEW_CODE,
    invitedEmails: [],
    createdAt: serverTimestamp(),
  });
  if (withInvite) batch.set(doc(db, `invites/${NEW_CODE}`), { spaceId: 's2' });
  return batch.commit();
}

test('anyone signed in can create a space with its invite, as its own adult owner', async () => {
  await assertSucceeds(newSpace(stranger(), 'stranger'));
  await assertFails(newSpace(as('other'), 'stranger'));
  await assertFails(newSpace(env.unauthenticatedContext().firestore(), 'stranger'));
  await assertFails(newSpace(stranger(), 'stranger', { role: 'child' }));
});

test('a space cannot be created without its invite document', async () => {
  await assertFails(newSpace(stranger(), 'stranger', { withInvite: false }));
});

test('members read their spaces with the query the app uses', async () => {
  const mine = (db, uid) => getDocs(query(collection(db, 'spaces'), where('memberIds', 'array-contains', uid)));
  await assertSucceeds(mine(kid(), 'kid'));
  await assertFails(mine(stranger(), 'member'));
  await assertFails(getDoc(doc(stranger(), SPACE)));
});

test('an invited, verified email sees the invitation and nobody else does', async () => {
  const invitations = (db, email) =>
    getDocs(query(collection(db, 'spaces'), where('invitedEmails', 'array-contains', email)));
  await assertSucceeds(invitations(guest(), 'guest@example.com'));
  await assertFails(invitations(stranger(), 'guest@example.com'));
  await assertFails(invitations(as('guest', 'guest@example.com', false), 'guest@example.com'));
});

test('an invite code is looked up one at a time and cannot be listed', async () => {
  await assertSucceeds(getDoc(doc(stranger(), `invites/${CODE}`)));
  await assertFails(getDocs(collection(stranger(), 'invites')));
});

test('joining needs the current invite code and makes a child', async () => {
  await assertFails(joinWithCode(stranger(), 'stranger', 'AAAA2222'));
  await assertFails(joinWithCode(stranger(), 'stranger', CODE, 'adult'));
  await assertSucceeds(joinWithCode(stranger(), 'stranger', CODE));
  // A new child sees the space, and nobody's documents.
  await assertSucceeds(getDoc(doc(stranger(), SPACE)));
  await assertFails(getDoc(doc(stranger(), OWNERS_PASSPORT)));
});

test('joining cannot add someone else or change anything else', async () => {
  await assertFails(joinWithCode(stranger(), 'other', CODE));
  await assertFails(
    updateDoc(doc(stranger(), SPACE), {
      memberIds: arrayUnion('stranger'),
      'members.stranger': { name: 'New', role: 'child', inviteCode: CODE },
      name: 'Mine now',
    }),
  );
});

test('the owner can reset the invite code and the old code stops working', async () => {
  await assertFails(resetCode(member()));
  await assertSucceeds(resetCode(owner()));
  await assertFails(joinWithCode(stranger(), 'stranger', CODE));
  await assertSucceeds(joinWithCode(stranger(), 'stranger', NEW_CODE));
});

test('the owner can remove a member, who then loses access, but cannot touch the others', async () => {
  const remove = (db, extra = {}) =>
    resetCode(db, { memberIds: arrayRemove('member'), 'members.member': deleteField(), ...extra });
  await assertFails(remove(member()));
  await assertFails(remove(owner(), { 'members.kid.role': 'adult' }));
  await assertSucceeds(remove(owner()));
  await assertFails(getDoc(doc(member(), SPACE)));
  await assertFails(getDoc(doc(member(), MEMBERS_TEST)));
});

test('only the owner can rename the space, and ownership cannot move', async () => {
  await assertSucceeds(updateDoc(doc(owner(), SPACE), { name: 'Home' }));
  await assertFails(updateDoc(doc(member(), SPACE), { name: 'Mine' }));
  await assertFails(updateDoc(doc(owner(), SPACE), { ownerId: 'member' }));
  await assertFails(updateDoc(doc(owner(), SPACE), { name: '' }));
});

test('members manage email invites and strangers do not', async () => {
  const invite = (db) => updateDoc(doc(db, SPACE), { invitedEmails: arrayUnion('friend@example.com') });
  await assertSucceeds(invite(kid()));
  await assertSucceeds(updateDoc(doc(member(), SPACE), { invitedEmails: arrayRemove('friend@example.com') }));
  await assertFails(invite(stranger()));
});

test('an invited person can accept as a child, which uses up the invitation', async () => {
  const accept = (db, uid, email, role = 'child') =>
    updateDoc(doc(db, SPACE), {
      memberIds: arrayUnion(uid),
      [`members.${uid}`]: { name: 'Guest', role },
      invitedEmails: arrayRemove(email),
    });
  await assertFails(accept(stranger(), 'stranger', 'guest@example.com'));
  await assertFails(accept(guest(), 'guest', 'guest@example.com', 'adult'));
  await assertSucceeds(accept(guest(), 'guest', 'guest@example.com'));
  await assertSucceeds(getDoc(doc(guest(), SPACE)));
});

test('an invited person can decline, and cannot remove other invitations', async () => {
  await env.withSecurityRulesDisabled((context) =>
    updateDoc(doc(context.firestore(), SPACE), { invitedEmails: arrayUnion('friend@example.com') }),
  );
  await assertFails(updateDoc(doc(guest(), SPACE), { invitedEmails: arrayRemove('friend@example.com') }));
  await assertSucceeds(updateDoc(doc(guest(), SPACE), { invitedEmails: arrayRemove('guest@example.com') }));
});

test('a member can leave, the owner cannot, and nobody can remove someone else', async () => {
  const leave = (db, uid) =>
    updateDoc(doc(db, SPACE), { memberIds: arrayRemove(uid), [`members.${uid}`]: deleteField() });
  await assertFails(leave(owner(), 'owner'));
  await assertFails(leave(member(), 'owner'));
  await assertSucceeds(leave(kid(), 'kid'));
});

test('a member changes their own name only, and only the name', async () => {
  const rename = (db, uid, name) => updateDoc(doc(db, SPACE), { [`members.${uid}.name`]: name });
  await assertSucceeds(rename(kid(), 'kid', 'Nikos'));
  await assertFails(rename(kid(), 'owner', 'Nikos'));
  await assertFails(rename(kid(), 'kid', ''));
  await assertFails(updateDoc(doc(kid(), SPACE), { 'members.kid.role': 'adult' }));
});

test('an adult makes another member an adult or a child; children, the owner and oneself are off limits', async () => {
  const setRole = (db, uid, role) => updateDoc(doc(db, SPACE), { [`members.${uid}.role`]: role });
  await assertFails(setRole(kid(), 'kid', 'adult'));
  await assertFails(setRole(kid(), 'member', 'child'));
  await assertFails(setRole(member(), 'owner', 'child'));
  await assertFails(setRole(member(), 'member', 'child'));
  await assertSucceeds(setRole(member(), 'kid', 'adult'));
  await assertSucceeds(setRole(owner(), 'kid', 'child'));
  // Last, because a write that changes nothing passes as an empty email-invite edit.
  await assertSucceeds(setRole(owner(), 'member', 'child'));
  // A made-up role such as 'boss' is not refused: the rules cannot name the entry that changed.
  // The app and the rules treat every role other than 'adult' as child, so it gives away nothing.
});

test('the owner hands the space to a member on the way out, who becomes an adult', async () => {
  const handOver = (db, to, extra = {}) =>
    updateDoc(doc(db, SPACE), { ownerId: to, memberIds: arrayRemove('owner'), 'members.owner': deleteField(), ...extra });
  await assertFails(handOver(member(), 'member'));
  await assertFails(handOver(owner(), 'stranger'));
  await assertFails(handOver(owner(), 'owner'));
  // A child cannot own the space as a child.
  await assertFails(handOver(owner(), 'kid'));
  await assertSucceeds(handOver(owner(), 'kid', { 'members.kid.role': 'adult' }));
});

test('only the owner can delete the space', async () => {
  const remove = (db) =>
    writeBatch(db).delete(doc(db, `invites/${CODE}`)).delete(doc(db, SPACE)).commit();
  await assertFails(remove(member()));
  await assertSucceeds(remove(owner()));
});

test("adults list everyone's documents, children only their own, with the queries the app uses", async () => {
  const all = (db) => getDocs(collection(db, DOCUMENTS));
  const own = (db, uid) => getDocs(query(collection(db, DOCUMENTS), where('ownerId', '==', uid)));
  await assertSucceeds(all(owner()));
  await assertSucceeds(all(member()));
  await assertFails(all(kid()));
  await assertSucceeds(own(kid(), 'kid'));
  await assertFails(own(kid(), 'owner'));
  await assertFails(all(stranger()));
  await assertFails(getDoc(doc(kid(), OWNERS_PASSPORT)));
  await assertSucceeds(getDoc(doc(owner(), KIDS_REPORT)));
});

test('a member adds documents for themselves, an adult also for a child, nobody for another adult', async () => {
  await assertSucceeds(setDoc(doc(kid(), `${DOCUMENTS}/n1`), newFile('kid', 'kid')));
  await assertSucceeds(setDoc(doc(owner(), `${DOCUMENTS}/n2`), newFile('kid', 'owner')));
  await assertSucceeds(setDoc(doc(member(), `${DOCUMENTS}/n3`), newLink('member', 'member')));
  await assertFails(setDoc(doc(kid(), `${DOCUMENTS}/n4`), newFile('owner', 'kid')));
  await assertFails(setDoc(doc(member(), `${DOCUMENTS}/n5`), newFile('owner', 'member')));
  await assertFails(setDoc(doc(owner(), `${DOCUMENTS}/n6`), newFile('stranger', 'owner')));
  await assertFails(setDoc(doc(stranger(), `${DOCUMENTS}/n7`), newFile('stranger', 'stranger')));
  // Who added it is the caller, at the server's time.
  await assertFails(setDoc(doc(owner(), `${DOCUMENTS}/n8`), newFile('owner', 'member')));
  await assertFails(setDoc(doc(owner(), `${DOCUMENTS}/n9`), newFile('owner', 'owner', { createdAt: new Date(0) })));
});

test('documents with a wrong shape are rejected', async () => {
  const add = (extra) => setDoc(doc(owner(), `${DOCUMENTS}/bad`), newFile('owner', 'owner', extra));
  await assertFails(add({ title: '' }));
  await assertFails(add({ title: 'x'.repeat(101) }));
  await assertFails(add({ kind: 'video' }));
  await assertFails(add({ category: 'pets' }));
  await assertFails(add({ tags: 'car' }));
  await assertFails(add({ tags: Array(11).fill('t') }));
  await assertFails(add({ favorite: 'yes' }));
  await assertFails(add({ expiresDay: 'soon' }));
  await assertFails(add({ note: 'x'.repeat(1001) }));
  await assertFails(add({ sizeBytes: 25 * 1024 * 1024 + 1 }));
  await assertFails(add({ thumbnail: Bytes.fromUint8Array(new Uint8Array(60001)) }));
  await assertFails(add({ text: 'x'.repeat(10001) }));
  await assertFails(add({ text: ['words'] }));
  await assertFails(add({ link: 'https://example.com' }));
  await assertFails(add({ secret: true }));
  const link = (url) => setDoc(doc(owner(), `${DOCUMENTS}/bad`), newLink('owner', 'owner', url));
  await assertFails(link('javascript:alert(1)'));
  await assertFails(link('intent://scan/#Intent;scheme=zxing;end'));
  await assertFails(link(`https://example.com/${'x'.repeat(2000)}`));
  await assertSucceeds(link('http://example.com'));
});

test("a document's words change, its file and owner do not", async () => {
  const edit = (db, path, data) => updateDoc(doc(db, path), data);
  const words = { title: 'Passport (new)', category: 'identity', tags: ['travel'], favorite: true, expiresDay: 21000, note: 'Renewed', updatedAt: serverTimestamp() };
  await assertSucceeds(edit(owner(), OWNERS_PASSPORT, words));
  await assertSucceeds(edit(owner(), KIDS_REPORT, words));
  await assertSucceeds(edit(kid(), KIDS_REPORT, { favorite: true }));
  await assertSucceeds(edit(owner(), OWNERS_PASSPORT, { uploaded: true }));
  await assertSucceeds(edit(owner(), OWNERS_PASSPORT, { text: 'PASSPORT HELLENIC REPUBLIC' }));
  await assertFails(edit(member(), OWNERS_PASSPORT, words));
  await assertFails(edit(kid(), OWNERS_PASSPORT, { favorite: true }));
  await assertFails(edit(owner(), OWNERS_PASSPORT, { ownerId: 'kid' }));
  await assertFails(edit(owner(), OWNERS_PASSPORT, { fileName: 'other.pdf' }));
  await assertFails(edit(owner(), OWNERS_PASSPORT, { addedById: 'kid' }));
  await assertFails(edit(stranger(), OWNERS_PASSPORT, { favorite: true }));
});

test('a document is deleted by its owner or by an adult over a child', async () => {
  await assertFails(deleteDoc(doc(kid(), OWNERS_PASSPORT)));
  await assertFails(deleteDoc(doc(member(), OWNERS_PASSPORT)));
  await assertFails(deleteDoc(doc(stranger(), KIDS_REPORT)));
  await assertSucceeds(deleteDoc(doc(owner(), KIDS_REPORT)));
  await assertSucceeds(deleteDoc(doc(member(), MEMBERS_TEST)));
  await assertSucceeds(deleteDoc(doc(owner(), OWNERS_PASSPORT)));
});

test('a child made adult sees everything, an adult made child sees only their own', async () => {
  await env.withSecurityRulesDisabled((context) =>
    updateDoc(doc(context.firestore(), SPACE), { 'members.kid.role': 'adult', 'members.member.role': 'child' }),
  );
  await assertSucceeds(getDoc(doc(kid(), OWNERS_PASSPORT)));
  await assertFails(getDoc(doc(member(), OWNERS_PASSPORT)));
  await assertSucceeds(getDoc(doc(member(), MEMBERS_TEST)));
});

test('only a person writes the tokens of their own devices', async () => {
  const token = (db, userId) => doc(db, `users/${userId}/tokens/abc`);
  await assertSucceeds(setDoc(token(member(), 'member'), { updatedAt: 1 }));
  await assertFails(setDoc(token(member(), 'member'), { updatedAt: 1, admin: true }));
  await assertFails(setDoc(token(member(), 'owner'), { updatedAt: 1 }));
  await assertFails(getDoc(token(owner(), 'member')));
  await assertSucceeds(deleteDoc(token(member(), 'member')));
});
