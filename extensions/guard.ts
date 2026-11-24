/**
 * pi-carry 审批闸门。
 *
 * bridge 通过 `-e guard.ts` 显式加载本扩展；所有工具调用先经 `ctx.ui.confirm`
 * 转成 RPC 的 extension_ui_request，由 bridge 决定放行或拒绝。
 * 本文件不直接访问网络，也不信任工具入参。
 */
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

/**
 * bridge 启动 pi 后会调 `get_commands` 检查这个名字是否存在。
 * 不存在说明扩展没被加载（`-e` 参数没生效、文件被删、pi 扩展 API 变了），
 * 此时 `--approve` 下所有工具调用都会无审批直通，所以 bridge 必须拒绝该会话。
 */
export const GUARD_COMMAND = "pi-carry-guard";

export default function (pi: ExtensionAPI) {
  // 网关存活探针：只注册命令，不产生副作用。
  pi.registerCommand(GUARD_COMMAND, {
    description: "pi-carry 审批网关自检（由 bridge 校验，请勿手动调用）",
    handler: async (_args, ctx) => {
      ctx.ui.notify("pi-carry 审批网关已加载", "info");
    },
  });

  pi.on("tool_call", async (event, ctx) => {
    const payload = {
      toolName: event.toolName,
      toolCallId: event.toolCallId,
      input: event.input ?? {},
    };
    const message = "PI_CARRY_APPROVAL:" + JSON.stringify(payload);
    // fail-closed：审批通道异常（RPC 断开、bridge 关闭、扩展 API 抛错）时必须拒绝，
    // 不能因为网关坏了就让工具调用直通——那与「闸门」的定位正好相反。
    let ok = false;
    try {
      ok = await ctx.ui.confirm("pi-carry 审批", message);
    } catch (err) {
      return {
        block: true,
        reason: "审批通道异常，已拒绝（pi-carry）：" + String(err),
      };
    }
    if (!ok) {
      return { block: true, reason: "用户拒绝（pi-carry）" };
    }
  });
}
