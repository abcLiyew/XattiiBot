package com.esdllm.common;

/**
 * B 站侧的<b>大数格式化</b>（播放量 / 热度 / 点赞…）。
 *
 * <p>单独抽出来是因为它被两处用到：链接解析（{@code BilibiliAnalysisImpl}）与搜索命令
 * （{@code BiliSearchPlugins}）。口径必须一致 —— 同一个播放量在两处显示成不同样子，
 * 用户第一反应是"数据是不是不一样"。
 *
 * <p>沿用项目既有口径：<b>超过 1 万</b>显示成 {@code x.xx万}，否则原样输出。
 * 注意是「超过」而不是「大于等于」—— 10000 本身显示 {@code 10000}，
 * 这是从原 {@code sendVideoMsg} 里原样搬过来的行为，别顺手"修正"。
 */
public final class NumFormat {

    private NumFormat() {
    }

    /**
     * 格式化大数。
     *
     * @param value 数值，可为 {@code null}（B 站字段常缺）；{@code null} 按 {@code 0} 处理
     * @return 形如 {@code 1234} 或 {@code 45.82万}
     */
    public static String count(Long value) {
        long count = value == null ? 0L : value;
        if (count > 10000) {
            return String.format("%.2f万", (double) count / 10000);
        }
        return String.valueOf(count);
    }
}
