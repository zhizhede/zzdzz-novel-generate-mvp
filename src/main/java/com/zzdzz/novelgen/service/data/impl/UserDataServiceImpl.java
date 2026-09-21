package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.UserMapper;
import com.zzdzz.novelgen.model.dto.UserDTO;
import com.zzdzz.novelgen.service.data.UserDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** users 数据服务实现。 */
@Service
public class UserDataServiceImpl extends ServiceImpl<UserMapper, UserDTO> implements UserDataService {

    @Override
    public UserDTO findAliveByUsername(String username) {
        return baseMapper.findAliveByUsername(username);
    }

    @Override
    public Long findIdByUsername(String username) {
        return baseMapper.findIdByUsername(username);
    }

    @Override
    public long insert(String username, String passwordHash, String role) {
        return baseMapper.insert(username, passwordHash, role);
    }
}
