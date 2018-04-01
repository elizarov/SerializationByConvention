package protoDescribe

import java.io.*
import java.util.*
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

/*
syntax = "proto3";

message Date {
    int32 year = 1;
    int32 month = 2;
    int32 date = 3;
}

message Person {
    string name = 1;
    int32 age = 2;
    Date born = 3;
    Date dead = 4;
}
*/

interface ProtoWriter<T> {
    val descriptor: String
    fun ProtoDescriber.describe()
    fun ProtoOutput.write(obj: T)
}

// --- descriptor: String ---

fun buildDescriptor(block: StringBuilder.() -> Unit): String = buildString(block)

fun StringBuilder.beginDescribe(name: String)  {
    append(name)
}

// --- PrintWriter.describe() --

@InjectSerializer
fun <T> ProtoDescriber.describe(desc: ProtoWriter<T>) = with(desc) { describe() }

@SerializationQualifier(Tag::class, SerialName::class)
fun <T : Int> ProtoDescriber.describeVarint(tag: Int, name: String) =
    describeProp(tag, name, "int32")

@SerializationQualifier(Tag::class, SerialName::class)
fun <T : String> ProtoDescriber.describeString(tag: Int, name: String) =
    describeProp(tag, name, "string")

@SerializationQualifier(Tag::class, SerialName::class)
@InjectSerializer
fun <T> ProtoDescriber.describeEmbedded(tag: Int, name: String, desc: ProtoWriter<T>) {
    describeProp(tag, name, desc.descriptor)
    push(desc)
}

// -- ProtoOutput.write(obj: T)

@InjectSerializer
fun <T> ProtoOutput.write(obj: T, writer: ProtoWriter<T>) = with(writer) { write(obj) }

@SerializationQualifier(Tag::class)
fun ProtoOutput.writeVarint(tag: Int, value: Int) {
    emitTagType(tag, 0)
    emitVarint(value)
}

@SerializationQualifier(Tag::class)
fun ProtoOutput.writeString(tag: Int, value: String) {
    emitTagType(tag, 2)
    emitBytes(value.toByteArray(Charsets.UTF_8))
}

@SerializationQualifier(Tag::class)
@InjectSerializer
fun <T> ProtoOutput.writeEmbedded(tag: Int, obj: T, writer: ProtoWriter<T>) {
    emitTagType(tag, 2)
    val offset = this.size
    emit(0) // reserve a byte
    write(obj, writer)
    val length = this.size - offset - 1 // actual length of embedded message
    patchVarint(offset, length)
}

object __DateProtoWriter : ProtoWriter<Date> {
    override val descriptor: String = buildDescriptor {
        beginDescribe("Date")
    }

    override fun ProtoDescriber.describe() {
        beginDescribe("Date")
        describeVarint<Int>(1, "year")
        describeVarint<Int>(2, "month")
        describeVarint<Int>(3, "day")
        endDescribe()
    }

    override fun ProtoOutput.write(obj: Date) {
        writeVarint(1, obj.year)
        writeVarint(2, obj.month)
        writeVarint(3, obj.day)
    }
}

object __PersonProtoWriter : ProtoWriter<Person> {
    override val descriptor: String = buildDescriptor {
        beginDescribe("Person")
    }

    override fun ProtoDescriber.describe() {
        beginDescribe("Person")
        describeString<String>(1, "name")
        describeVarint<Int>(2, "age")
        describeEmbedded<Date>(3, "born", __DateProtoWriter)
        describeEmbedded<Date>(4, "dead", __DateProtoWriter)
        endDescribe()
    }

    override fun ProtoOutput.write(obj: Person) {
        writeString(1, obj.name)
        writeVarint(2, obj.age)
        writeEmbedded(3, obj.born, __DateProtoWriter)
        writeEmbedded(4, obj.dead, __DateProtoWriter)
    }
}

// --- test code ---

fun main(args: Array<String>) {
    // print .proto
    ProtoDescriber().printProto(__PersonProtoWriter)
    // write a person
    val output = ProtoOutput()
    val person = Person("Elvis", 42,
        born = Date(1935, 1, 8),
        dead = Date(1977, 8, 16)
    )
    output.write(person, __PersonProtoWriter)
    val bytes = output.toByteArray()
    println(bytes.joinToString(separator = "") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
}

// --- aux annotations ---

annotation class Serializable(vararg val serializers: KClass<*>)
annotation class SerializationQualifier(vararg val annotations: KClass<*>)
annotation class SerialName(val name: String)
annotation class InjectSerializer

// --- describer --

class ProtoDescriber(val out: PrintStream = System.out) {
    private val seen = HashSet<ProtoWriter<*>>()
    private val queue = ArrayDeque<ProtoWriter<*>>()

    fun push(desc: ProtoWriter<*>) {
        if (seen.add(desc)) queue += desc
    }

    fun printProto(desc: ProtoWriter<*>) {
        push(desc)
        while (!queue.isEmpty())
            describe(queue.poll())
    }

    fun beginDescribe(name: String) {
        out.println("message $name {")
    }

    fun describeProp(tag: Int, name: String, type: String) {
        out.println("    $type $name = $tag;")
    }

    fun endDescribe() {
        out.println("}")
        out.println()
    }
}

// --- proto ---

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
        val hi = x ushr 7
        if (hi == 0) return acc + 1
        return varintLength(hi, acc + 1)
    }

    private tailrec fun storeVarint(offset: Int, x: Int) {
        val lo = x and 0x7f
        val hi = x ushr 7
        if (hi == 0) {
            buf[offset] = lo.toByte()
            return
        }
        buf[offset] = (lo or 0x80).toByte()
        storeVarint(offset + 1, hi)
    }

    fun emitTagType(tag: Int, type: Int) =
        emitVarint(tag shl 3 or type)

    fun emitVarint(x: Int) {
        val n = varintLength(x)
        ensure(size + n)
        storeVarint(size, x)
        size += n
    }

    fun patchVarint(offset: Int, x: Int) {
        val n = varintLength(x)
        ensure(size + n - 1)
        if (n > 1) System.arraycopy(buf, offset + 1, buf, offset + n, size - offset - 1)
        storeVarint(offset, x)
        size += n - 1
    }

    fun emitBytes(bytes: ByteArray) {
        emitVarint(bytes.size)
        emit(bytes)
    }
}
