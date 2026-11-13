
export interface CamelotKey {
  num: number;
  letter: 'A' | 'B';
}

export interface TrackTags {
  title: string;
  artist: string;
  album?: string;
}

export const ANALYSIS_VERSION = 8;

export interface BeatSide {
  beats: number[];
  downbeats: number[];
  bpm: number;
  confidence: number;
}

export interface VocalWindow {
  startSec: number;
  fps: number;
  curve: number[];
}

export interface LocalGrid {
  bpm: number;
  beatIntervalSec: number;
  firstDownbeatSec: number;
  gridConfidence: number;
}

export interface TrackAnalysis {
  fileKey: string;
  path: string;
  tags: TrackTags;
  durationSec: number;
  bpm: number | null;
  beatIntervalSec: number | null;
  firstDownbeatSec: number | null;
  gridConfidence: number;
  camelot: CamelotKey | null;
  keyName: string | null;
  keyConfidence: number;
  energy: number;
  energyCurve: number[];
  introEndSec: number | null;
  outroStartSec: number | null;
  tailGrid?: LocalGrid | null;
  headBeats?: BeatSide | null;
  tailBeats?: BeatSide | null;
  musicVideo?: boolean;
  vocals?: VocalWindow[];
  analysisVersion: number;
  manualOverrides?: {
    bpm?: number;
    camelot?: CamelotKey;
    gridNudgeBeats?: number;
  };
}
