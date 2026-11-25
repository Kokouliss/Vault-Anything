import assert from 'node:assert/strict';
import { test } from 'node:test';
import { birthdayAt, todayInAthens } from '../functions/dates.js';

const day = (date) => Date.parse(date) / 86_400_000;

test('an 18th birthday is 18 years after the birth day, and 29 February falls on 28 February', () => {
  assert.equal(birthdayAt(day('2008-03-12'), 18), day('2026-03-12'));
  assert.equal(birthdayAt(day('2008-02-29'), 18), day('2026-02-28'));
  // Eighteen years on from a leap year is never one; twenty is.
  assert.equal(birthdayAt(day('2008-02-29'), 20), day('2028-02-29'));
  assert.equal(birthdayAt(day('1969-12-31'), 18), day('1987-12-31'));
});

test('today in Greece is today or a neighbouring day in UTC', () => {
  assert.ok(Math.abs(todayInAthens() - Math.floor(Date.now() / 86_400_000)) <= 1);
});
