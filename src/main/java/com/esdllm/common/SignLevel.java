package com.esdllm.common;


import lombok.Getter;


/**
 * 好感度等级。
 *
 * <p>每个枚举值是"累计经验达到多少就进这一级"的阈值，{@link com.esdllm.model.respObj.SignInInfo#buildSignLevel}
 * 取<b>第一个</b>阈值 ≥ 经验值的等级。
 *
 * <p>🔴 <b>这些字段刻意是只读的（没有 setter）</b>。枚举常量是 JVM 级单例，
 * 一旦有人对它调 setter，改的是<b>全局唯一的那个对象</b>，所有后续判断一起错 ——
 * 2026-11-20 就这么炸过一次：超高级的人查了一次等级，`ZERO` 被改成"一百万 / 超级无敌挚友"，
 * 于是<b>全群都变成最高等级</b>，重启前谁也修不好。要加新档位就加枚举项，不要改常量。
 */
@Getter
public enum SignLevel {
    ZERO(15.0,"路人","排斥"),
    ONE(40.0,"陌生","冷漠"),
    TWO(70.0,"相遇","警惕"),
    THREE(120.0,"初识","可以交流"),
    FOUR(180.0,"熟悉","友善"),
    FIVE(250.0,"熟知","信任"),
    SIX(330.0,"深交","喜爱"),
    SEVEN(420.0,"情深","热情"),
    EIGHT(520.0,"情投意合","亲密"),
    NINE(630.0,"挚友","无话不谈"),
    /** 超过 NINE 之后的彩蛋档。阈值取 1000：既有明确上界，也不至于让 631 的人"距离下一级 369"太怪 */
    TEN(1000.0,"超级无敌挚友","无话不谈");

    private final Double empirical;

    private final String opinion;

    private final String attitude;

    SignLevel(Double empirical, String opinion, String attitude) {
        this.empirical = empirical;
        this.opinion = opinion;
        this.attitude = attitude;
    }

    /**
     * 是不是最高档。
     *
     * <p>用来把"距离下一级"显示成"已满级" —— 否则会算出 {@code 1000 - 999} 这种
     * 或者（更糟）拿一个极大阈值减出来一串天文数字。
     */
    public boolean isMax() {
        return this == TEN;
    }

    @Override
    public String toString() {
        return "好感度等级："+this.opinion+"\n对你的态度："+this.attitude;
    }
}
