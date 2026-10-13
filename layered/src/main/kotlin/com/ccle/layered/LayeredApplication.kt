package com.ccle.layered

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class LayeredApplication

fun main(args: Array<String>) {
    runApplication<LayeredApplication>(*args)
}
