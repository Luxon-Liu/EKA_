package com.liu.eka.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liu.eka.entity.user.EkUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表访问入口：注册写行、登录按账号查行
 *
 * @author Luxon
 * @date 2026/09/22
 */
@Mapper
public interface EkUserMapper extends BaseMapper<EkUser> {
}
