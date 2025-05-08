package com.esdllm.service;

import com.esdllm.model.SignInRecords;
import com.baomidou.mybatisplus.extension.service.IService;
import com.esdllm.model.respObj.SignInInfo;

/**
* @author LiYehe
* @description 针对表【sign_in_records】的数据库操作Service
* @createDate 2025-04-23 00:15:03
*/
public interface SignInRecordsService extends IService<SignInRecords> {

    SignInInfo isSign(Long qqUid, Long groupId);
}