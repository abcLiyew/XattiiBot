package com.esdllm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.esdllm.model.SignInRecords;
import com.esdllm.model.respObj.SignInInfo;
import com.esdllm.service.SignInRecordsService;
import com.esdllm.mapper.SignInRecordsMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;

/**
* @author LiYehe
* @description 针对表【sign_in_records】的数据库操作Service实现
* @createDate 2025-04-23 00:15:03
*/
@Service
public class SignInRecordsServiceImpl extends ServiceImpl<SignInRecordsMapper, SignInRecords>
    implements SignInRecordsService{
    SignInInfo signInInfo = new SignInInfo();
    @Override
    public SignInInfo isSign(Long qqUid, Long groupId) {
        LambdaQueryWrapper<SignInRecords> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SignInRecords::getQqUid,qqUid);
        if (!Objects.isNull(groupId)){
            queryWrapper.eq(SignInRecords::getGroupId,groupId);
        }
        SignInRecords signInRecords = this.getOne(queryWrapper);
        if (sign(signInRecords,qqUid,groupId)) {
            return signInInfo;
        }
        return null;
    }

    private boolean sign(SignInRecords signInRecords, Long qqUid,Long groupId){
        if (todaySigned(signInRecords)){
            signInInfo.setIsSign(false);
            return true;
        }
        if (Objects.isNull(signInRecords)){
            signInRecords = new SignInRecords();
            signInRecords.setQqUid(qqUid);
            if (!Objects.isNull(groupId)){
                signInRecords.setGroupId(groupId);
            }
            signInRecords.setCreateTime(System.currentTimeMillis());
        }
        double addEmpirical = Math.random()*2;
        BigDecimal addEmpiricalTwo = new BigDecimal(addEmpirical);

        double empirical = signInRecords.getEmpirical()+addEmpiricalTwo.setScale(2, RoundingMode.HALF_UP).doubleValue();
        BigDecimal empiricalTwo = new BigDecimal(empirical);

        signInRecords.setEmpirical(empiricalTwo.setScale(2, RoundingMode.HALF_UP).doubleValue());
        signInRecords.setUpdateTime(System.currentTimeMillis());
        boolean save = signInRecords.getSid()==null?this.save(signInRecords):this.updateById(signInRecords);
        signInInfo.setAddEmpirical(addEmpiricalTwo.setScale(2, RoundingMode.HALF_UP).doubleValue());
        signInInfo.setEmpirical(signInRecords.getEmpirical());
        signInInfo.setUid(signInRecords.getSid());
        signInInfo.setSignLevel(SignInInfo.buildSignLevel(signInInfo.getEmpirical()));
        signInInfo.setIsSign(true);
        return  save;
    }
    private boolean todaySigned(SignInRecords signInRecords){
        if (Objects.isNull(signInRecords)){
            return false;
        }
        Date updateTime = new Date(signInRecords.getUpdateTime());
        Date now = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        String todayDate = sdf.format(now);
        String updateDate = sdf.format(updateTime);
        return todayDate.equals(updateDate) && signInRecords.getEmpirical() != 0.0;
    }
}




