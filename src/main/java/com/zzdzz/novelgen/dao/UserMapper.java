package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.dto.UserDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** users 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/UserMapper.xml。 */
public interface UserMapper extends BaseMapper<UserDTO> {

    UserDTO findAliveByUsername(@Param("username") String username);

    Long findIdByUsername(@Param("username") String username);

    long insert(@Param("username") String username, @Param("passwordHash") String passwordHash, @Param("role") String role);
}
