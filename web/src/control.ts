import type { ControlCommand, GlobalAction } from "./types";

export class ControlSender {
  private sequence = 0;
  constructor(private readonly sendRaw: (value: string) => void) {}
  tap(x: number, y: number) { this.send({ type: "tap", sequence: ++this.sequence, x: clamp(x), y: clamp(y) }); }
  swipe(fromX: number, fromY: number, toX: number, toY: number, durationMs: number) {
    this.send({ type: "swipe", sequence: ++this.sequence, fromX: clamp(fromX), fromY: clamp(fromY), toX: clamp(toX), toY: clamp(toY), durationMs: Math.max(50, Math.min(2000, durationMs)) });
  }
  longPress(x: number, y: number) { this.send({ type: "longPress", sequence: ++this.sequence, x: clamp(x), y: clamp(y) }); }
  action(action: GlobalAction) { this.send({ type: "globalAction", sequence: ++this.sequence, action }); }
  setText(text: string) { if (text.length > 2000) throw new Error("Text is limited to 2,000 characters"); this.send({ type: "setText", sequence: ++this.sequence, text }); }
  private send(command: ControlCommand) { this.sendRaw(JSON.stringify(command)); }
}
export const clamp = (value: number) => Math.max(0, Math.min(1, value));

export function videoPoint(x:number,y:number,width:number,height:number,frameWidth:number,frameHeight:number){
  if(width<=0||height<=0||frameWidth<=0||frameHeight<=0)return undefined;
  const scale=Math.min(width/frameWidth,height/frameHeight);
  const w=frameWidth*scale,h=frameHeight*scale;
  const nx=(x-(width-w)/2)/w,ny=(y-(height-h)/2)/h;
  return Number.isFinite(nx)&&Number.isFinite(ny)&&nx>=0&&nx<=1&&ny>=0&&ny<=1?{x:nx,y:ny}:undefined;
}
