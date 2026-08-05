package com.v2ray.ang.handler

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Мгновенная скорость в байтах в секунду. */
data class TrafficSpeed(
    val proxyUp: Long = 0L,
    val proxyDown: Long = 0L,
    val directUp: Long = 0L,
    val directDown: Long = 0L,
    /** Всё, что не опознано как proxy или direct: в чужих конфигах теги называют как угодно. */
    val otherUp: Long = 0L,
    val otherDown: Long = 0L
) {
    val totalUp: Long get() = proxyUp + directUp + otherUp
    val totalDown: Long get() = proxyDown + directDown + otherDown
}

/**
 * Общая точка со скоростью для интерфейса.
 *
 * Считает её [NotificationManager]: счётчики ядра отдаются с обнулением, поэтому опрашивать
 * их из двух мест нельзя - каждый забирал бы часть трафика другого, и обе цифры врали бы.
 * Здесь лежит результат того единственного опроса.
 */
/** Сколько прокачано с момента подключения, в байтах. */
data class SessionTraffic(val up: Long = 0L, val down: Long = 0L) {
    val total: Long get() = up + down
}

object TrafficSpeedState {

    /**
     * Замер в виде строки для передачи между процессами: ядро крутится в своём процессе,
     * и общий объект туда не дотягивается.
     */
    fun encode(speed: TrafficSpeed, intervalSeconds: Double): String = listOf(
        speed.proxyUp, speed.proxyDown,
        speed.directUp, speed.directDown,
        speed.otherUp, speed.otherDown,
        (intervalSeconds * 1000).toLong()
    ).joinToString(",")

    /** Разбирает строку из [encode]; на мусор отвечает null, чтобы не ронять приём. */
    fun decode(payload: String): Pair<TrafficSpeed, Double>? {
        val parts = payload.split(',')
        if (parts.size != 7) return null
        val values = parts.map { it.trim().toLongOrNull() ?: return null }
        return TrafficSpeed(
            proxyUp = values[0], proxyDown = values[1],
            directUp = values[2], directDown = values[3],
            otherUp = values[4], otherDown = values[5]
        ) to values[6] / 1000.0
    }

    /** Сколько замеров держим для графика: при опросе раз в 3 секунды это около двух минут. */
    const val HISTORY_SIZE = 40

    private val _speed = MutableStateFlow(TrafficSpeed())
    val speed: StateFlow<TrafficSpeed> = _speed.asStateFlow()

    /** История суммарной скорости для графика: от старых замеров к свежим. */
    private val _history = MutableStateFlow<List<TrafficSpeed>>(emptyList())
    val history: StateFlow<List<TrafficSpeed>> = _history.asStateFlow()

    private val _session = MutableStateFlow(SessionTraffic())
    val session: StateFlow<SessionTraffic> = _session.asStateFlow()

    /**
     * @param value Скорость за прошедший интервал.
     * @param intervalSeconds Длина интервала: из неё считается объём, ушедший в счётчик сессии.
     */
    fun publish(value: TrafficSpeed, intervalSeconds: Double) {
        _speed.value = value
        _history.value = (_history.value + value).takeLast(HISTORY_SIZE)

        if (intervalSeconds > 0) {
            val current = _session.value
            _session.value = SessionTraffic(
                up = current.up + (value.totalUp * intervalSeconds).toLong(),
                down = current.down + (value.totalDown * intervalSeconds).toLong()
            )
        }
    }

    /** Обнуляет и мгновенные значения, и историю: соединения больше нет. */
    fun reset() {
        _speed.value = TrafficSpeed()
        _history.value = emptyList()
        _session.value = SessionTraffic()
    }
}
