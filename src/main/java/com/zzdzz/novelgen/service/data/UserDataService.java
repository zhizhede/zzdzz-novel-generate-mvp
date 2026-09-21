package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.UserDTO;

import java.util.List;

/** users 数据服务接口（原 UserDAO）。 */
public interface UserDataService extends IService<UserDTO> {

    UserDTO findAliveByUsername(String username);

    Long findIdByUsername(String username);

    long insert(String username, String passwordHash, String role);
}
