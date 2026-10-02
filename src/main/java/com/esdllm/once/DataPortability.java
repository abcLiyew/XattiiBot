package com.esdllm.once;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.esdllm.mapper.SignInRecordsMapperMysql;
import com.esdllm.model.SignInRecords;
import com.esdllm.model.SignInRecordsMysql;
import com.esdllm.service.SignInRecordsService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DataPortability {


    @Resource
    private SignInRecordsMapperMysql signInRecordsMapperMysql;
    @Resource
    private SignInRecordsService signInRecordsService;
    //@PostConstruct
    public void mysqlDataToSqlite(){
        ///**
        //     * 用户uid
        //     */
        //    @TableId(type = IdType.AUTO)
        //    private Integer sid;
        //
        //    /**
        //     * 用户qq号
        //     */
        //    private Long qqUid;
        //
        //    /**
        //     * 群号
        //     */
        //    private Long groupId;
        //
        //    /**
        //     * 经验值
        //     */
        //    private Double empirical = 0.0;
        //
        //    /**
        //     * 创建时间戳
        //     */
        //    private Long createTime = System.currentTimeMillis();
        //
        //    /**
        //     * 更新时间戳
        //     */
        //    private Long updateTime = System.currentTimeMillis();
        //
        //    /**
        //     * 逻辑删除 0-未删除 1-已删除
        //     */
        //    @TableLogic
        //    private Integer isDelete = 0;
        List<SignInRecordsMysql> mysqlSignInRecordsList = getMysqlSignInRecords();
        List<SignInRecords> sqliteSignInRecordsList = new ArrayList<>();
        for (SignInRecordsMysql recordsMysql : mysqlSignInRecordsList) {
            SignInRecords signInRecords = getSignInRecords(recordsMysql);
            sqliteSignInRecordsList.add(signInRecords);
        }
        insertSignInRecords(sqliteSignInRecordsList);
        System.out.println("数据迁移完成");
    }

    private static SignInRecords getSignInRecords(SignInRecordsMysql recordsMysql) {
        SignInRecords signInRecords = new SignInRecords();
        signInRecords.setSid(recordsMysql.getSid());
        signInRecords.setQqUid(recordsMysql.getQqUid());
        signInRecords.setGroupId(recordsMysql.getGroupId());
        signInRecords.setEmpirical(recordsMysql.getEmpirical());
        signInRecords.setCreateTime(recordsMysql.getCreateTime().getTime());
        signInRecords.setUpdateTime(recordsMysql.getUpdateTime().getTime());
        signInRecords.setIsDelete(recordsMysql.getIsDelete());
        return signInRecords;
    }

    @DS("mysql")
    private List<SignInRecordsMysql> getMysqlSignInRecords(){
        return signInRecordsMapperMysql.selectList(null);
    }
    @DS("sqlite")
    private void insertSignInRecords(List<SignInRecords> signInRecordsList){
        signInRecordsService.saveBatch(signInRecordsList);
    }
}
