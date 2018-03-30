package protoRead

import kotlin.reflect.*

annotation class Tag(val tag: Int)

@Serializable(ProtoReader::class)
data class Date(
    @Tag(1) val year: Int,
    @Tag(2) val month: Int,
    @Tag(3) val day: Int
)

@Serializable(ProtoReader::class)
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

interface ProtoReader<T> {
    fun ProtoInput.read(): T
}

private annotation class TagType(val tagType: Int)
private annotation class WireType(val type: Int)

@SerializationQualifier(TagType::class)
fun ProtoInput.nextRead(): Int =
    if (hasMore()) fetchVarint() else -1

@SerializationQualifier(Tag::class, WireType::class, TagType::class)
fun convertToTagType(tag: Int, type: Int): Int = (tag shl 3) or type

@WireType(0)
fun ProtoInput.readVarint() = fetchVarint()

@WireType(2)
fun ProtoInput.readString(): String {
    val n = fetchVarint()
    return String(fetchBytes(n), Charsets.UTF_8)
}

// top-level read function
@InjectSerializer
@WireType(2)
fun <T> ProtoInput.read(reader: ProtoReader<T>): T = with(reader) { read() }

@InjectSerializer
@WireType(2)
fun <T> ProtoInput.readEmbedded(reader: ProtoReader<T>): T {
    val length = fetchVarint() // length of embedded message
    val oldLimit = limit // preserve current limit
    limit = offset + length // set limit for embedded message
    return read(reader).also { limit = oldLimit }
}

@SerializationQualifier(TagType::class)
fun ProtoInput.skipRead(tagType: Int) {
    error("Unexpected tag+type $tagType before offset $offset")
}

object __DateProtoReader : ProtoReader<Date> {
    override fun ProtoInput.read(): Date {
        var year = 0
        var month = 0
        var day = 0
        var bitMask = 0
        loop@while (true) {
            val nextTagType = nextRead()
            when (nextTagType) {
                convertToTagType(1, 0) -> {
                    year = readVarint()
                    bitMask = bitMask or 0x01
                }
                convertToTagType(2, 0) -> {
                    month = readVarint()
                    bitMask = bitMask or 0x02
                }
                convertToTagType(3, 0) -> {
                    day = readVarint()
                    bitMask = bitMask or 0x04
                }
                -1 -> break@loop
                else -> skipRead(nextTagType)
            }
        }
        require(bitMask == 0x07) { "Some required properties are missing" }
        return Date(year, month, day)
    }
}

object __PersonProtoReader : ProtoReader<Person> {
    override fun ProtoInput.read(): Person {
        var name: String? = null
        var age: Int = 0
        var born: Date? = null
        var dead: Date? = null
        var bitMask = 0
        loop@while (true) {
            val nextTagType = nextRead()
            when (nextTagType) {
                convertToTagType(1, 2) -> {
                    name = readString()
                    bitMask = bitMask or 0x01
                }
                convertToTagType(2, 0) -> {
                    age = readVarint()
                    bitMask = bitMask or 0x02
                }
                convertToTagType(3, 2) -> {
                    born = readEmbedded(__DateProtoReader)
                    bitMask = bitMask or 0x04
                }
                convertToTagType(4, 2) -> {
                    dead = readEmbedded(__DateProtoReader)
                    bitMask = bitMask or 0x08
                }
                -1 -> break@loop
                else -> skipRead(nextTagType)
            }
        }
        require(bitMask == 0x0F) { "Some required properties are missing" }
        return Person(name!!, age, born!!, dead!!)
    }
}

// --- test code ---

fun main(args: Array<String>) {
    //                      0 1 2 3 4 5 6 7 8 91011121314151617181920212223242526
    val bytes = "0a05456c766973102a1a07088f0f10011808220708b90f10081810"
        .windowed(2, 2).map { it.toInt(16).toByte() }.toByteArray()
    val input = ProtoInput(bytes)
    println(input.read(__PersonProtoReader))
}

// ---- aux annotations ---

annotation class Serializable(vararg val interfaces: KClass<*>)
annotation class SerializationQualifier(vararg val interfaces: KClass<*>)
annotation class InjectSerializer

// ---- proto ---

class ProtoInput(private val buf: ByteArray) {
    var offset = 0
        private set
    var limit = buf.size

    fun hasMore() = offset < limit

    fun fetchBytes(n: Int): ByteArray = buf.copyOfRange(offset, offset + n).also { offset += n }

    fun fetchVarint(): Int {
        var res = 0
        while (true) {
            val b = buf[offset++].toInt() and 0xff
            res = (res shl 7) or (b and 0x7f)
            if (b and 0x80 == 0) return res
        }
    }
}
