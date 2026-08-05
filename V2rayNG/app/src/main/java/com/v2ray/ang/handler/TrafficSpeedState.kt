package com.v2ray.ang.handler

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Мгновенная скорость в байтах в секунду. */
data class TrafficSpeed(
    val proxyUp: Long = 0L,
    val proxyDown: Long = 0L,
    val directUp: Long = 0L,
    val directDown: Long = 0L
) {
    val totalUp: Long get() = proxyUp + directUp
    val totalDown: Long get() = proxyDown + directDown
}

/**
 * Общая точка со скоростью для интерфейса.
 *
 * Считает её [NotificationManager]: счётчики ядра отдаются с обнулением, поэтому опрашивать
 * их из двух мест нельзя - каждый забирал бы часть трафика другого, и обе цифры врали бы.
 * Здесь лежит результат того единственного опроса.
 */
object TrafficSpeedState {
    private val _speed = MutableStateFlow(TrafficSpeed())
    val speed: StateFlow<TrafficSpeed> = _speed.asStateFlow()

    fun publish(value: TrafficSpeed) {
        _speed.value = value
    }

    fun reset() {
        _speed.value = TrafficSpeed()
    }
}
