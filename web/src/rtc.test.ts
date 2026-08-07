import{describe,expect,it,vi}from"vitest";
import{parseControlMessage,sendControlPayload}from"./rtc";

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
