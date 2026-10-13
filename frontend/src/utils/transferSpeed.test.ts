import { expect, it } from "vitest";
import { createSpeedSampler, formatSpeed } from "./transferSpeed";
it("按字节差采样并排除已恢复分片", () => {
  let time = 0;
  const sample = createSpeedSampler(() => time);
  expect(sample(500000)).toBe(0);
  time = 1000;
  expect(sample(501024)).toBe(1024);
  time = 2000;
  expect(sample(501024)).toBe(0);
  expect(sample(0)).toBe(0);
  time = 3000;
  expect(sample(2048)).toBe(2048);
});
it("显示速度单位并保护无效值", () => {
  expect(formatSpeed(1024)).toBe("1.0 KB/s");
  expect(formatSpeed(1048576)).toBe("1.0 MB/s");
  expect(formatSpeed(NaN)).toBe("0 B/s");
});
