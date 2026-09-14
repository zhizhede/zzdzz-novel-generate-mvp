package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.UserDO;

import java.util.List;

/** users 数据服务接口（原 UserDAO）。 */
public interface UserDataService extends IService<UserDO> {

    UserDO findAliveByUsername(String username);

    Long findIdByUsername(String username);

    long insert(String username, String passwordHash, String role);
}
