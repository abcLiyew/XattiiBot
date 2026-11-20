package com.esdllm.model.respObj;

import com.esdllm.common.SignLevel;
import lombok.Data;

@Data
public class SignInInfo{
    Boolean isSign; // 是否签到成功
    Double addEmpirical;
    Integer uid;
    Double empirical;
    SignLevel signLevel;

    public Double distanceNext(){
        // 已满级就没有"下一级"了：返回 0，让调用方显示"已满级"而不是拿阈值减出天文数字
        if (signLevel == null || signLevel.isMax()) {
            return 0.0;
        }
        return signLevel.getEmpirical()-empirical;
    }

    /**
     * 经验值 → 等级：取第一个"阈值 ≥ 经验值"的档位。
     *
     * <p>🔴 <b>这里曾经这么写过</b>（2026-11-20 修掉）：
     * <pre>
     * SignLevel signLevel = SignLevel.ZERO;    // 拿到的是全局单例枚举常量
     * signLevel.setEmpirical(1000000.0);       // 直接把 ZERO 本身改掉了
     * signLevel.setOpinion("超级无敌挚友");
     * </pre>
     * 后果：只要有人经验超过最高档（630），ZERO 就被永久改成"一百万 / 超级无敌挚友"，
     * 之后<b>所有人</b>在循环第一轮就 {@code 经验 <= 一百万} 命中并返回它
     * ⇒ 全群显示最高等级，而且**不重启好不了**（枚举是 JVM 级单例）。
     *
     * <p>正确做法就是现在这样：超档返回一个<b>真实存在的</b>枚举项 {@link SignLevel#TEN}，
     * 绝不去改常量。枚举字段也早已去掉 setter，从结构上堵住这条路。
     */
    public static SignLevel buildSignLevel(Double empirical){
        double value = empirical == null ? 0.0 : empirical;
        for (SignLevel signLevel : SignLevel.values()){
            if (value<=signLevel.getEmpirical()){
                return signLevel;
            }
        }
        return SignLevel.TEN;
    }
}