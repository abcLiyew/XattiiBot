package com.esdllm.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 签到数据
 * @TableName sign_in_records
 */
@TableName(value ="sign_in_records")
@Data
public class SignInRecords {
    /**
     * 用户uid
     */
    @TableId(type = IdType.AUTO)
    private Integer sid;

    /**
     * 用户qq号
     */
    private Long qqUid;

    /**
     * 群号
     */
    private Long groupId;

    /**
     * 经验值
     */
    private Double empirical = 0.0;

    /**
     * 创建时间戳
     */
    private Long createTime = System.currentTimeMillis();

    /**
     * 更新时间戳
     */
    private Long updateTime = System.currentTimeMillis();

    /**
     * 逻辑删除 0-未删除 1-已删除
     */
    @TableLogic
    private Integer isDelete = 0;

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that == null) {
            return false;
        }
        if (getClass() != that.getClass()) {
            return false;
        }
        SignInRecords other = (SignInRecords) that;
        return (this.getSid() == null ? other.getSid() == null : this.getSid().equals(other.getSid()))
            && (this.getQqUid() == null ? other.getQqUid() == null : this.getQqUid().equals(other.getQqUid()))
            && (this.getGroupId() == null ? other.getGroupId() == null : this.getGroupId().equals(other.getGroupId()))
            && (this.getEmpirical() == null ? other.getEmpirical() == null : this.getEmpirical().equals(other.getEmpirical()))
            && (this.getCreateTime() == null ? other.getCreateTime() == null : this.getCreateTime().equals(other.getCreateTime()))
            && (this.getUpdateTime() == null ? other.getUpdateTime() == null : this.getUpdateTime().equals(other.getUpdateTime()))
            && (this.getIsDelete() == null ? other.getIsDelete() == null : this.getIsDelete().equals(other.getIsDelete()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getSid() == null) ? 0 : getSid().hashCode());
        result = prime * result + ((getQqUid() == null) ? 0 : getQqUid().hashCode());
        result = prime * result + ((getGroupId() == null) ? 0 : getGroupId().hashCode());
        result = prime * result + ((getEmpirical() == null) ? 0 : getEmpirical().hashCode());
        result = prime * result + ((getCreateTime() == null) ? 0 : getCreateTime().hashCode());
        result = prime * result + ((getUpdateTime() == null) ? 0 : getUpdateTime().hashCode());
        result = prime * result + ((getIsDelete() == null) ? 0 : getIsDelete().hashCode());
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName());
        sb.append(" [");
        sb.append("Hash = ").append(hashCode());
        sb.append(", sid=").append(sid);
        sb.append(", qqUid=").append(qqUid);
        sb.append(", groupId=").append(groupId);
        sb.append(", empirical=").append(empirical);
        sb.append(", createTime=").append(createTime);
        sb.append(", updateTime=").append(updateTime);
        sb.append(", isDelete=").append(isDelete);
        sb.append("]");
        return sb.toString();
    }
}