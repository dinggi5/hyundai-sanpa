package studio.scopic.sanpa

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.SystemClock
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener

/**
 * 차가 「지금 말해도 되는 때인가」를 고르는 데 쓰는 값들.
 *  - 기어(Pleos Vehicle): 주차(P)로 들어오는 순간이 물음을 꺼낼 때다.
 *  - 동승석 착석(Pleos Vehicle): 옆에 누가 있으면 가족의 한마디를 틀지 않는다.
 *  - 주행 제한(AAOS CarUxRestrictions): 달리는 동안에는 화면 조작을 받지 않는다.
 *  - 음악(AudioManager): 음악이 나오는 동안에는 끼어들지 않고 기다린다.
 * 콜백은 전부 [main] 스레드로 넘긴다(SDK 리스너는 다른 스레드에서 온다).
 */
class CarGate(
    private val context: Context,
    private val main: Handler,
    private val log: (String) -> Unit,
    private val onChange: () -> Unit,
    private val onParked: () -> Unit,
) {
    var gear: VehicleGear? = null; private set
    var passenger: Boolean? = null; private set
    private var uxRestricted = false
    /** 처음 값을 다 읽었는가(또는 읽기를 기다릴 만큼 기다렸는가). 그 전에는 「혼자다」라고 넘겨짚지 않는다. */
    var settled = false; private set
    private var answers = 0

    /** 모르는 값은 막지 않는다 — 기어를 못 읽는 차에서 앱이 통째로 잠기지 않게. 아는데 P 가 아니면 막는다. */
    val canTouch: Boolean get() = settled && !uxRestricted && (gear == null || gear == VehicleGear.GEAR_PARK)
    /** 주차해 있는 것이 확인됐는가. 저절로 묻는 것은 이것이 참일 때만. */
    val parked: Boolean get() = gear == VehicleGear.GEAR_PARK
    /** 앞으로 달리는 중(D)인가. 같이 탔을 때 물음은 이때만 낸다 — 후진·중립에서는 묻지 않는다. */
    val forward: Boolean get() = gear == VehicleGear.GEAR_DRIVE
    /** 주차가 아닌 것이 확인됐는가(모르는 것과 다르다). */
    val moving: Boolean get() = gear != null && gear != VehicleGear.GEAR_PARK
    /** 옆자리가 비었는가. 못 읽으면 비었다고 본다. */
    val alone: Boolean get() = passenger != true
    /** 착석 값이 방금 바뀌지 않았는가. 켠 직후에는 값이 잠깐 튄다(실측: 같은 순간에 true·false·true) — 가라앉은 뒤에 믿는다. */
    val steady: Boolean get() = SystemClock.elapsedRealtime() - seatChangedAt >= STEADY_MS
    private var seatChangedAt = 0L
    val musicPlaying: Boolean get() = context.getSystemService(AudioManager::class.java).isMusicActive

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null
    private var running = false
    private var gen = 0 // 켤 때마다 올린다 — 지난번에 건 조회·리스너의 늦은 답을 버린다
    private val seat = SeatArea(SeatValue.ROW_1_RIGHT)

    // 리스너는 켤 때마다 새로 만들어 그때의 세대를 쥔다 — 지난번 SDK 의 늦은 콜백이 지금 세대로 통과하지 않게(코덱스 개발 6 R1).
    private var gearListener: GearSelectionListener? = null
    private var seatListener: SeatOccupancyListener? = null
    // 리스너가 값을 한 번이라도 줬으면, 그 뒤에 도착한 처음 조회의 답은 옛 값이다 — 값은 버리고 「답이 왔다」만 센다(코덱스 개발 6 R1).
    private var gearHeard = false
    private var seatHeard = false

    // 처음 조회의 답은, 값을 적은 다음에 「답이 왔다」고 센다. 순서가 바뀌면 값이 비어 있는 채로 판단이 한 번 돈다(개발 3 실측).
    private fun setGear(g: VehicleGear?, fromListener: Boolean) {
        if (!running) return
        // 모르는 값(null)을 준 리스너는 「들었다」로 치지 않는다 — 그 뒤의 처음 조회가 진짜 값일 수 있다(코덱스 개발 6 R2).
        if (fromListener) { if (g != null) gearHeard = true }
        else if (gearHeard) { answered(); return }
        val before = gear
        if (g != null && g != gear) {
            gear = g
            log("기어 $before → $g")
        }
        if (!fromListener) answered()
        if (g == null || g == before) return
        onChange()
        // 달리다 세운 순간만 센다. 처음 읽은 값이 P 인 것은 「주차했다」가 아니다.
        if (fromListener && before != null && before != VehicleGear.GEAR_PARK && g == VehicleGear.GEAR_PARK) onParked()
    }

    private fun answered() {
        if (++answers >= 2 && !settled) { settled = true; onChange() }
    }

    private fun setPassenger(occupied: Boolean?, first: Boolean = false) {
        if (!running) return
        // 🔴 Vehicle SDK 2.3.2 + 에뮬레이터 v2.1.0 에서는 이 값이 뒤집혀 온다(개발 3 실측).
        // Virtual Console 에서 「Occupied」로 두면(VHAL SEAT_OCCUPANCY=2) SDK 는 false 를, 「Empty」(=1)면 true 를 준다.
        // 운전석·동승석 둘 다 그렇다. SDK 가 고쳐지면 SEAT_VALUE_INVERTED 만 내린다.
        if (!first) { if (occupied != null) seatHeard = true }
        else if (seatHeard) { answered(); return }
        val seated = occupied?.let { if (SEAT_VALUE_INVERTED) !it else it }
        val changed = seated != null && seated != passenger
        if (changed) {
            passenger = seated
            seatChangedAt = SystemClock.elapsedRealtime()
            log("동승석 착석 → $seated")
        }
        if (first) answered()
        if (changed) onChange()
    }

    fun start() {
        if (running) return
        running = true
        val at = ++gen
        val v = Vehicle(context)
        vehicle = v
        v.initialize()
        // initialize 에 완료 콜백이 없다. 잠깐 뒤에 부른다(개발 2 실측).
        main.postDelayed({
            if (!running || vehicle !== v) return@postDelayed
            try {
                v.getGear().getCurrentGearSelectionState({ g -> main.post { if (at == gen) setGear(g, fromListener = false) } },
                    { log("기어 조회 실패 $it"); main.post { if (at == gen && running) answered() } })
                val gl = object : GearSelectionListener {
                    override fun onSelected(g: VehicleGear?) { main.post { if (at == gen) setGear(g, fromListener = true) } }
                    override fun onFailed(e: Exception) = log("기어 리스너 실패 $e")
                }
                gearListener = gl
                v.getGear().registerGearSelection(gl)
                v.getSeat().getCurrentSeatOccupancy(seat, { o -> main.post { if (at == gen) setPassenger(o, first = true) } },
                    { log("착석 조회 실패 $it"); main.post { if (at == gen && running) answered() } })
                val sl = object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(occupied: Boolean?) { main.post { if (at == gen) setPassenger(occupied) } }
                    override fun onFailed(e: Exception) = log("착석 리스너 실패 $e")
                }
                seatListener = sl
                v.getSeat().registerSeatOccupancy(seat, sl)
            } catch (e: Exception) {
                log("차 API 예외 $e")
            }
        }, 1500)
        main.postDelayed({ if (running && vehicle === v && !settled) { settled = true; onChange() } }, SETTLE_MS)
        try {
            car = Car.createCar(context)
            ux = (car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager)?.also { m ->
                uxRestricted = m.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
                m.registerListener { r ->
                    main.post {
                        if (!running || at != gen) return@post
                        uxRestricted = r.isRequiresDistractionOptimization
                        log("주행 제한 → $uxRestricted")
                        onChange()
                    }
                }
            }
        } catch (e: Throwable) {
            log("주행 제한을 읽지 못함 $e") // AAOS 가 아닌 기기. 기어만으로 가린다.
        }
    }

    fun stop() {
        if (!running) return
        running = false
        gen++
        try {
            gearListener?.let { vehicle?.getGear()?.unregisterGearSelection(it) }
            seatListener?.let { vehicle?.getSeat()?.unregisterSeatOccupancy(seat, it) }
        } catch (_: Exception) {}
        try { vehicle?.release() } catch (_: Exception) {}
        vehicle = null
        try { ux?.unregisterListener(); car?.disconnect() } catch (_: Throwable) {}
        ux = null; car = null
        // 다음에 켤 때 처음 읽는 값이 「바뀐 것」으로 세어지지 않게 비운다.
        gearListener = null; seatListener = null; gearHeard = false; seatHeard = false
        gear = null; passenger = null; uxRestricted = false; settled = false; answers = 0
    }

    companion object {
        const val SEAT_VALUE_INVERTED = true
        const val STEADY_MS = 2000L
        const val SETTLE_MS = 5000L // 차 값이 끝내 안 오면 이만큼 기다리고 넘어간다
    }
}
