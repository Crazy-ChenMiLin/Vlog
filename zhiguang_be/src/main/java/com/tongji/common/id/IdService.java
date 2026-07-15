package com.tongji.common.id;

import com.tongji.knowpost.id.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IdService {
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public long nextId() {
        return snowflakeIdGenerator.nextId();
    }

    public String nextIdString() {
        return String.valueOf(nextId());
    }
}
