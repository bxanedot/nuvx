import { StarFilledIcon, StarIcon } from '@/components/icons/Transport';
import { useLikeStatus, useSetLiked, useSignedIn } from '@/data/music/queries';

interface FavoriteButtonProps {
  videoId: string | null;
  className?: string;
  size?: number;
}

export function FavoriteButton({ videoId, className, size = 18 }: FavoriteButtonProps) {
  const signedIn = useSignedIn();
  const status = useLikeStatus(videoId);
  const setLiked = useSetLiked();
  if (!signedIn || !videoId) return null;
  const liked = status.data === 'LIKE';
  return (
    <button
      className={className}
      aria-label={liked ? 'Unfavorite' : 'Favorite'}
      aria-pressed={liked}
      disabled={status.isPending}
      onClick={(event) => {
        event.stopPropagation();
        setLiked.mutate({ videoId, liked: !liked });
      }}
    >
      {liked ? <StarFilledIcon size={size} /> : <StarIcon size={size} />}
    </button>
  );
}
