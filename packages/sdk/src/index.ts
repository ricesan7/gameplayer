export type InputState = {
  keys: Set<string>;
  mouse: { x: number; y: number; down: boolean };
};

export type GameAPI = {
  canvas: HTMLCanvasElement;
  ctx: CanvasRenderingContext2D;
  loadImage: (url: string) => Promise<HTMLImageElement>;
  playBeep: () => void;
  /** 将来拡張用: 親へ通知（親側で無視可） */
  setButtons?: (names: string[]) => void;
};

export interface GameModule {
  init?(api: GameAPI): Promise<void> | void;
  update?(dt: number, input: InputState): void;
  render?(ctx: CanvasRenderingContext2D): void;
}
