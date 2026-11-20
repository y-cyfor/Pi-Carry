/**
 * pi-carry 审批闸门。
 *
 * bridge 通过 `-e guard.ts` 显式加载本扩展；所有工具调用先经 `ctx.ui.confirm`
 * 转成 RPC 的 extension_ui_request，由 bridge 决定放行或拒绝。
 * 本文件不直接访问网络，也不信任工具入参。
 */
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

export default function (pi: ExtensionAPI) {
  pi.on("tool_call", async (event, ctx) => {
    if (process.env.PI_CARRY_GUARD === "0") return;
    const payload = {
      toolName: event.toolName,
      toolCallId: event.toolCallId,
      input: event.input ?? {},
    };
    const message = "PI_CARRY_APPROVAL:" + JSON.stringify(payload);
    const ok = await ctx.ui.confirm("pi-carry 审批", message);
    if (!ok) {
      return { block: true, reason: "用户拒绝（pi-carry）" };
    }
  });
}
