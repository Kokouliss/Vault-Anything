// Day arithmetic shared by the scheduled functions. A day is a count of days since 1 January 1970,
// the same count the app keeps in expiresDay and birthDay.
const DAY_MILLIS = 86_400_000;

/** Today's date in Greece. */
function todayInAthens() {
  return Math.floor(Date.parse(new Date().toLocaleDateString('en-CA', { timeZone: 'Europe/Athens' })) / DAY_MILLIS);
}

/** The day someone born on `birthDay` turns `years`. A 29 February birthday falls on 28 February, as LocalDate.plusYears says in the app. */
function birthdayAt(birthDay, years) {
  const born = new Date(birthDay * DAY_MILLIS);
  const same = Date.UTC(born.getUTCFullYear() + years, born.getUTCMonth(), born.getUTCDate());
  return (new Date(same).getUTCMonth() === born.getUTCMonth() ? same : same - DAY_MILLIS) / DAY_MILLIS;
}

module.exports = { DAY_MILLIS, todayInAthens, birthdayAt };
