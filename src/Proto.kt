package proto

import kotlin.reflect.*

annotation class Tag(val tag: Int)

@Serializable(ProtoWriter::class)
data class Date(
    @Tag(1) val year: Int,
    @Tag(2) val month: Int,
    @Tag(3) val day: Int
)

@Serializable(ProtoWriter::class)
data class Person(
    @Tag(1) val name: String,
    @Tag(2) val age: Int,
    @Tag(3) val born: Date,
    @Tag(4) val dead: Date)

interface ProtoWriter<T> {
    fun ProtoOutput.write(obj: T)
}

@InjectSerializer
fun <T> ProtoOutput.write(obj: T, writer: ProtoWriter<T>) = with(writer) { write(obj) }

@SerializationQualifier(Tag::class)
fun ProtoOutput.writeVarint(tag: Int, value: Int) {
    emitVarint(tag shl 3)
    emitVarint(value)
}

@SerializationQualifier(Tag::class)
fun ProtoOutput.writeString(tag: Int, value: String) {
    emitVarint(tag shl 3 or 2)
    emitBytes(value.toByteArray(Charsets.UTF_8))
}

@SerializationQualifier(Tag::class)
@InjectSerializer
fun <T> ProtoOutput.write(tag: Int, obj: T, writer: ProtoWriter<T>) {
    emitVarint(tag shl 3 or 2)
    val pos = this.size
    emit(0) // reserve a byte
    write(obj, writer)
    patchVarint(pos, this.size - pos - 1)
}

object __DateProtoWriter : ProtoWriter<Date> {
    override fun ProtoOutput.write(obj: Date) {
        writeVarint(1, obj.year)
        writeVarint(2, obj.month)
        writeVarint(3, obj.day)
    }
}

object __PersonProtoWriter : ProtoWriter<Person> {
    override fun ProtoOutput.write(obj: Person) {
        writeString(1, obj.name)
        writeVarint(2, obj.age)
        write(3, obj.born, __DateProtoWriter)
        write(4, obj.dead, __DateProtoWriter)
    }
}

// --- test code ---

fun main(args: Array<String>) {
    val output = ProtoOutput()
    val person = Person("Elvis", 42,
        born = Date(1935, 1, 8),
        dead = Date(1977, 8, 16)
    )
    output.write(person, __PersonProtoWriter)
    val bytes = output.toByteArray()
    println(bytes.joinToString(separator = "") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
}

// ---- aux annotations ---

annotation class Serializable(vararg val interfaces: KClass<*>)
annotation class SerializationQualifier(vararg val interfaces: KClass<*>)
annotation class InjectSerializer

// ---- proto ---

class ProtoOutput() {
    private var buf = ByteArray(16)
    var size = 0
        private set

    fun toByteArray() = buf.copyOf(size)

    private fun ensure(capacity: Int) {
        if (buf.size < capacity) buf = buf.copyOf(capacity.coerceAtLeast(2 * buf.size))
    }

    fun emit(x: Int) {
        ensure(size + 1)
        buf[size++] = x.toByte()
    }

    private fun emit(bytes: ByteArray) {
        ensure(size + bytes.size)
        System.arraycopy(bytes, 0, buf, size, bytes.size)
        size += bytes.size
    }

    private tailrec fun varintLength(x: Int, acc: Int = 0): Int {
        val lo = x and 0x7f
        val hi = x ushr 7
        if (hi == 0) return acc + 1
        return varintLength(hi, acc + 1)
    }

    private tailrec fun storeVarint(pos: Int, x: Int) {
        val lo = x and 0x7f
        val hi = x ushr 7
        if (hi == 0) {
            buf[pos] = lo.toByte()
            return
        }
        buf[pos] = (lo or 0x80).toByte()
        storeVarint(pos + 1, hi)
    }

    fun emitVarint(x: Int) {
        val n = varintLength(x)
        ensure(size + n)
        storeVarint(size, x)
        size += n
    }

    fun patchVarint(pos: Int, x: Int) {
        val n = varintLength(x)
        ensure(size + n - 1)
        if (n > 1) System.arraycopy(buf, pos + 1, buf, pos + n, size - pos - 1)
        storeVarint(pos, x)
        size += n - 1
    }

    fun emitBytes(bytes: ByteArray) {
        emitVarint(bytes.size)
        emit(bytes)
    }
}
