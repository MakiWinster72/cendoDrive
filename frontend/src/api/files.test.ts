import { describe, expect, it } from "vitest";
import { uploadErrorMessage } from "./files";
const error = (code: string, status = 409) => ({ isAxiosError: true, response: { status, data: { code, message: "An item with the same name already exists", fields: {} } } });
describe("上传错误提醒", () => {
  it("同名冲突提示改名或更换目录", () => {
    expect(uploadErrorMessage(error("NAME_CONFLICT"))).toBe("当前目录已存在同名项目，请重命名文件或选择其他文件夹后上传");
  });
  it("容量不足提示清理空间", () => {
    expect(uploadErrorMessage(error("QUOTA_EXCEEDED"))).toContain("空间不足");
  });
  it("未知错误不展示后端原始英文", () => {
    expect(uploadErrorMessage(error("UNKNOWN"))).toBe("上传失败，请重试");
  });
});
