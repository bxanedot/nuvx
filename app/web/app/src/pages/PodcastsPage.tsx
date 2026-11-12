import { PageHeader } from '@/components/PageHeader';
import { Shelves } from '@/components/music/ShelfView';
import { LoadError, NeedsExtension } from '@/components/music/States';
import { usePodcasts, useYouTubeReady } from '@/data/music/queries';
import { NewSkeleton } from '@/skeletons/PageSkeletons';

export default function PodcastsPage() {
  const { missing } = useYouTubeReady();
  const podcasts = usePodcasts();
  if (missing) return <><PageHeader title="Podcasts" /><NeedsExtension /></>;
  if (podcasts.isError) return <><PageHeader title="Podcasts" /><LoadError error={podcasts.error} onRetry={() => void podcasts.refetch()} /></>;
  if (!podcasts.data) return <NewSkeleton />;
  return <><PageHeader title="Podcasts" /><Shelves shelves={podcasts.data} /></>;
}
