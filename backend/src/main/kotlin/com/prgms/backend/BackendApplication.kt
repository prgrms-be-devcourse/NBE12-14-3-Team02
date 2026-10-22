package com.prgms.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableJpaAuditing
@EnableScheduling // 마감기한을 위한 스위치
class BackendApplication

fun main(args: Array<String>) {
    runApplication<BackendApplication>(*args)
}
