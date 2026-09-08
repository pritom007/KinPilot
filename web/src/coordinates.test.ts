import{describe,it,expect}from"vitest";
import{videoPoint}from"./control";

describe("aspect-fit remote coordinates",()=>{
  it("maps portrait content and rejects the side bars",()=>{
    expect(videoPoint(500,250,1000,1000,500,1000)).toEqual({x:.5,y:.25});
    expect(videoPoint(20,250,1000,1000,500,1000)).toBeUndefined();
  });
  it("maps landscape after rotation and rejects top bars",()=>{
    expect(videoPoint(750,500,1000,1000,1000,500)).toEqual({x:.75,y:.5});
    expect(videoPoint(500,20,1000,1000,1000,500)).toBeUndefined();
  });
  it("rejects missing frames and invalid coordinates",()=>{
    expect(videoPoint(0,0,100,100,0,0)).toBeUndefined();
    expect(videoPoint(NaN,1,100,100,100,100)).toBeUndefined();
  });
});
