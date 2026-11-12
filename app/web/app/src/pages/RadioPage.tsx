import { useEffect, useState, type FormEvent } from 'react';
import { PageHeader } from '@/components/PageHeader';
import { RadioIcon } from '@/components/icons/NavIcons';
import { PlayIcon } from '@/components/icons/Transport';
import { Artwork } from '@/components/music/Artwork';
import { EmptyState, LoadError } from '@/components/music/States';
import { useRadioCountries, useRadioStations } from '@/data/music/queries';
import { countRadioClick } from '@/data/music/api';
import type { Track } from '@/data/music/types';
import { usePlayer } from '@/state/player';

const row = 'flex w-full items-center gap-3 rounded-[10px] p-2 text-left hover:bg-quinary focus-visible:outline-2 focus-visible:outline-key';

export default function RadioPage() {
  const countries = useRadioCountries();
  const [country, setCountry] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const stations = useRadioStations(search ? null : country || null, search);
  const current = usePlayer((state) => state.nowPlaying?.videoId);
  const playing = usePlayer((state) => state.status === 'playing' || state.status === 'buffering');

  useEffect(() => {
    if (!country && countries.data?.length) {
      const region = new Intl.Locale(navigator.language).region;
      setCountry(countries.data.find((item) => item.iso_3166_1 === region)?.iso_3166_1 ?? countries.data[0]!.iso_3166_1);
    }
  }, [countries.data, country]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setSearch(searchInput.trim());
  };
  const tune = (track: Track) => {
    countRadioClick(track.videoId.slice('radio:'.length));
    usePlayer.getState().playTrack(track, { title: 'Internet Radio', type: 'other', id: 'radio' });
  };

  return (
    <>
      <PageHeader title="Radio" />
      <div className="mx-(--body-gutter) mt-5 max-w-[760px]">
        <form className="flex gap-2" onSubmit={submit}>
          <input className="min-w-0 flex-1 rounded-pill bg-quinary px-4 py-2 type-body" value={searchInput} onChange={(event) => setSearchInput(event.target.value)} placeholder="Search stations" aria-label="Search radio stations" />
          <button className="rounded-pill bg-key-bg px-4 type-body-emphasized text-white" type="submit">Search</button>
          {search && <button className="rounded-pill px-3 type-body text-secondary hover:bg-quinary" type="button" onClick={() => { setSearch(''); setSearchInput(''); }}>Country</button>}
        </form>
        {!search && countries.data && <div className="mt-4 flex gap-2 overflow-x-auto pb-2">{countries.data.map((item) => <button key={item.iso_3166_1} className="shrink-0 rounded-pill bg-quinary px-3 py-1.5 type-callout aria-pressed:bg-key-bg aria-pressed:text-white" aria-pressed={country === item.iso_3166_1} onClick={() => setCountry(item.iso_3166_1)}>{item.iso_3166_1 === country ? '● ' : ''}{item.name}</button>)}</div>}
        {countries.isError && !search && <LoadError error={countries.error} onRetry={() => void countries.refetch()} />}
        {stations.isError && <LoadError error={stations.error} onRetry={() => void stations.refetch()} />}
        {!stations.isError && stations.isPending && <div className="py-12 text-center type-body text-secondary">Loading stations…</div>}
        {stations.data?.length === 0 && <EmptyState title="No stations found" />}
        <ul aria-label="Radio stations" className="mt-3 flex max-h-[65vh] flex-col gap-1 overflow-y-auto overscroll-contain">{stations.data?.map((station) => {
          const onAir = current === station.videoId;
          return <li key={station.videoId}><button className={row} onClick={() => tune(station)} aria-label={`Play ${station.title}`}>
            <div className="relative size-12 shrink-0 overflow-hidden rounded-[8px] bg-quinary">
              {station.thumbnail ? <Artwork src={station.thumbnail} displayWidth={96} radius="8px" /> : <RadioIcon size={24} className="absolute inset-0 m-auto text-secondary" />}
            </div>
            <span className="min-w-0 flex-1"><span className={`block truncate type-body-emphasized ${onAir ? 'text-key' : ''}`}>{station.title}</span><span className="block truncate type-callout text-secondary">{station.artists[0]?.name ?? 'Live radio'}</span></span>
            {onAir && playing ? <span className="type-callout-emphasized text-key">On Air</span> : <PlayIcon size={18} className="mr-2 text-secondary" />}
          </button></li>;
        })}</ul>
      </div>
    </>
  );
}
