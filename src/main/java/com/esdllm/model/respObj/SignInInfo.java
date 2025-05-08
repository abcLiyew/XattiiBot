package com.esdllm.model.respObj;

import com.esdllm.common.SignLevel;
import lombok.Data;

import java.util.Date;

@Data
public class SignInInfo{
    Boolean isSign; // 是否签到成功
    Double addEmpirical;
    Integer uid;
    Double empirical;
    SignLevel signLevel;

    public Double distanceNext(){
        return signLevel.getEmpirical()-empirical;
    }
    public static SignLevel buildSignLevel(Double empirical){
        for (SignLevel signLevel : SignLevel.values()){
            if (empirical<=signLevel.getEmpirical()){
                return signLevel;
            }
        }
        SignLevel signLevel = SignLevel.ZERO;
        signLevel.setEmpirical(1000000.0);
        signLevel.setOpinion("超级无敌挚友");
        signLevel.setAttitude("无话不谈");
        return signLevel;
    }
}