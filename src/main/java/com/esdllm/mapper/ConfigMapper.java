package com.esdllm.mapper;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.esdllm.model.Config;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.springframework.stereotype.Repository;

/**
* @author LiYehe
* @description 针对表【config】的数据库操作Mapper
* @createDate 2025-04-22 16:39:41
* @Entity esdllm.model.Config
*/
@Repository
@DS("sqlite")
public interface ConfigMapper extends BaseMapper<Config> {

}




