import{describe,expect,it,vi}from"vitest";
import{HelperRtcSession,parseControlMessage,sendControlPayload}from"./rtc";
import type{RendezvousClient}from"./rendezvous";

describe("sendControlPayload",()=>{
  it("does not send when the data channel is closed",()=>{
    const send=vi.fn();
    expect(sendControlPayload({readyState:"closed",send} as Pick<RTCDataChannel,"readyState"|"send">,"payload")).toBe(false);
    expect(send).not.toHaveBeenCalled();
  });

  it("converts native send failures into an unavailable result",()=>{
    const channel={readyState:"open",send:()=>{throw new Error("closed");}} as Pick<RTCDataChannel,"readyState"|"send">;
    expect(sendControlPayload(channel,"payload")).toBe(false);
  });
});

describe("control readiness handshake",()=>{
  it("requests readiness even when the channel opened before the observer attached, retries, and stops on close",()=>{
    vi.useFakeTimers();
    class Peer { close=vi.fn(); ondatachannel?: (event:{channel:unknown})=>void; }
    vi.stubGlobal("RTCPeerConnection",Peer);
    try{
      const rtc=new HelperRtcSession({sendSignal:vi.fn()} as unknown as RendezvousClient);
      const channel={readyState:"open",send:vi.fn(),close:vi.fn(),onmessage:undefined as undefined|((e:{data:string})=>void)};
      (rtc.peer as unknown as Peer).ondatachannel?.({channel});
      expect(channel.send).toHaveBeenCalledWith('{"type":"controlStatusRequest"}');
      const ready=vi.fn();rtc.onControlStatus=ready;
      vi.advanceTimersByTime(2000);
      expect(channel.send).toHaveBeenCalledTimes(2);
      channel.onmessage?.({data:'{"type":"controlStatus","ready":true,"reason":null}'});
      expect(ready).toHaveBeenCalledWith({ready:true});
      rtc.close();
      vi.advanceTimersByTime(4000);
      expect(channel.send).toHaveBeenCalledTimes(2);
    }finally{vi.useRealTimers();vi.unstubAllGlobals();}
  });
});

describe("parseControlMessage",()=>{
  it("parses ready control status",()=>{
    expect(parseControlMessage('{"type":"controlStatus","ready":true,"reason":null}')).toEqual({kind:"status",status:{ready:true}});
  });

  it("preserves actionable unavailable reason",()=>{
    expect(parseControlMessage('{"type":"controlStatus","ready":false,"reason":"accessibility_unavailable"}')).toEqual({
      kind:"status",
      status:{ready:false,reason:"accessibility_unavailable"},
    });
  });

  it("keeps command results separate and ignores malformed status",()=>{
    const result={sequence:3,accepted:false,reason:"accessibility_unavailable"};
    expect(parseControlMessage(JSON.stringify(result))).toEqual({kind:"result",value:result});
    expect(parseControlMessage('{"type":"controlStatus","ready":"yes"}')).toBeUndefined();
    expect(parseControlMessage("not-json")).toBeUndefined();
  });
});
