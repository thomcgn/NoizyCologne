import {scenario, candidate} from './discovery.mjs';

// Entirely synthetic: neither the preview nor tests use a live data source.
export function controlCenterFixture(state = 'populated') {
  const parties = Array.from({length: 64}, (_, index) => ({id: `party-${index}`, name: index === 0 ? 'NEON PARADISE' : `Alternative Reihe ${String(index).padStart(2, '0')}`, instagram: `party_${index}`, color: ['#53B4D8', '#A5A6FF', '#D8A653'][index % 3], residentDjIds: [], otherDjIds: [], organizerIds: [], standardGenres: ['Indie', 'Emo'], djMentionDefault: 'manual', recurrence: null, defaultFlyer: null}));
  const venues = Array.from({length: 24}, (_, index) => ({id: `venue-${index}`, name: index === 0 ? 'Club Beispiel' : `Location ${String(index).padStart(2, '0')}`, instagram: `location_${index}`, websiteUrl: 'https://quelle.example/', eventsUrl: '', discoveryEnabled: true, discoveryStatus: 'EVENTS_FOUND'}));
  const actors = Array.from({length: 75}, (_, index) => ({id: `actor-${index}`, name: index === 0 ? 'DJ Beispiel' : `Akteur ${String(index).padStart(2, '0')}`, instagram: `dj_${index}`, kind: index % 6 === 0 ? 'collective' : 'person', roles: ['dj'], collectiveIds: []}));
  const entries = Array.from({length: 95}, (_, index) => ({id: `entry-${index}`, type: 'event', date: `2027-01-${String(2 + index % 25).padStart(2, '0')}`, locationId: venues[index % venues.length].id, partyId: parties[index % parties.length].id, startTime: '22:00', entryPrice: '10 €', notes: '', mentionDjIds: [], eventFlyer: null, eventStatus: 'ACTIVE'}));
  const drafts = [{id: 'draft-0', calendarEntryId: 'entry-0', caption: 'Manuell geprüfte Caption\nZeilenumbrüche bleiben erhalten.', hashtags: '#noizycologne', mentionActorIds: [], genres: ['Indie'], status: 'DRAFT', musicTrackId: null, approvalNeedsReview: true, publicationBlockers: ['Terminangaben prüfen']}];
  const discovery = scenario(state === 'source-error' ? 'source-error' : state === 'empty' ? 'empty' : state === 'running' ? 'running' : 'desktop');
  discovery.candidates = state === 'empty' ? [] : [...Array.from({length: 48}, (_, index) => candidate(`new-${index}`, index === 0 ? 'MIDNIGHT / INDIE CLUB' : `Neue Partyreihe ${String(index).padStart(2, '0')}`)), discovery.candidates.at(-1)];
  const genres = ['Indie', 'Emo', 'Rock'].map((name, index) => ({id: `genre-${index}`, canonicalName: name.toLowerCase(), displayName: name, active: true, aliases: []}));
  if (state === 'empty') return {parties: [], venues: [], actors: [], entries: [], drafts: [], genres, discovery};
  return {parties, venues, actors, entries, drafts, genres, discovery};
}

export async function mockControlCenterApi(context, data, {onRequest, state = 'populated'} = {}) {
  await context.route('**/api/v1/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    onRequest?.({path: url.pathname, method: request.method(), body: request.postData()});
    if (state === 'error' && url.pathname !== '/api/v1/genres') return route.fulfill({status: 503, contentType: 'application/json', body: JSON.stringify({message: 'Testquelle vorübergehend nicht erreichbar'})});
    let response;
    if (url.pathname === '/api/v1/discovery') {
      const filter = url.searchParams.get('filter');
      response = {...data.discovery, candidates: ['NEEDS_REVIEW', 'READY', 'NEEDS_INQUIRY'].includes(filter) ? data.discovery.candidates.filter(item => item.status === filter) : data.discovery.history.filter(item => filter === 'IMPORTED' ? item.status === 'IMPORTED' : filter === 'AUTOMATIC_IGNORED' ? item.status === 'IGNORED' : true)};
    } else if (url.pathname === '/api/v1/discovery/changes') response = {changes: [], cancellations: []};
    else if (/\/discovery\/candidates\/[^/]+$/.test(url.pathname)) response = data.discovery.candidates.find(item => item.id === url.pathname.split('/').at(-1));
    else if (url.pathname.endsWith('/sources')) response = data.discovery.sources;
    else if (url.pathname === '/api/v1/parties') response = data.parties;
    else if (url.pathname === '/api/v1/venues') response = data.venues;
    else if (url.pathname === '/api/v1/actors') response = data.actors;
    else if (url.pathname === '/api/v1/calendar-entries') response = data.entries;
    else if (url.pathname === '/api/v1/editorial-drafts') response = data.drafts;
    else if (url.pathname === '/api/v1/genres') response = data.genres;
    else if (url.pathname === '/api/v1/music/tracks') response = [];
    else if (url.pathname.startsWith('/api/v1/discovery/scan') || url.pathname.startsWith('/api/v1/discovery/monitor')) response = null;
    else response = [];
    return route.fulfill({status: response === null ? 204 : 200, contentType: 'application/json', body: response === null ? undefined : JSON.stringify(response ?? {})});
  });
}
