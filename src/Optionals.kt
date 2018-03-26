package optionals

import java.util.*
import kotlin.reflect.*

enum class Transport { PLAIN, ENCRYPTED }

@Serializable(NodeReader::class)
data class Connection(val host: String?, val port: Int, val mode: Transport = Transport.PLAIN)

@Serializable(NodeReader::class)
data class Config(val log: Connection?, val data: Connection)

// enum support
inline fun <reified T : Enum<T>> Node.readEnum(): T =
    enumValueOf(readString().toUpperCase(Locale.ROOT))

class Node(private val prop: Properties, private val prefix: String = "") {
    // reading functions
    fun readString(): String = prop.getProperty(prefix)
    fun readInt(): Int = prop.getProperty(prefix).toInt()
    // child operator
    fun child(name: String) = Node(prop, key(name))
    // auxiliary
    private fun key(name: String) = if (prefix == "") name else "$prefix.$name"
    fun hasNode(): Boolean = prop.keys.any { it is String && it.startsWith(prefix) }
}

// optional properties support
fun Node.canRead(): Boolean = hasNode()

interface NodeReader<T> {
    fun Node.read(): T
}

@InjectSerializer
fun <T> Node.read(reader: NodeReader<T>) = with(reader) { read() }

@InjectSerializer
fun <T> Node.readNullable(reader: NodeReader<T>): T? =
    if (hasNode()) read(reader) else null

object __StringNodeReader : NodeReader<String> {
    override fun Node.read(): String = readString()
}

object __ConnectionNodeReader : NodeReader<Connection> {
    override fun Node.read(): Connection {
        val host = child("host").readNullable(__StringNodeReader)
        val port = child("port").readInt()
        val mode: Transport = with(child("mode")) {
            if (canRead()) readEnum<Transport>() else Transport.PLAIN
        }
        return Connection(host, port, mode)
    }
}

object __ConfigNodeReader : NodeReader<Config> {
    override fun Node.read(): Config {
        val log = child("log").readNullable(__ConnectionNodeReader)
        val data = child("data").read(__ConnectionNodeReader)
        return Config(log, data)
    }
}

// --- test code ---

fun main(args: Array<String>) {
    val props = Properties()
    props["log.host"] = "logger.example.com"
    props["log.port"] = "514"
    props["data.host"] = "incoming.example.com"
    props["data.port"] = "7011"
    props["data.mode"] = "encrypted"
    println(Node(props).read(__ConfigNodeReader))
}

// ---- aux annotations ---

annotation class Serializable(vararg val interfaces: KClass<*>)
annotation class InjectSerializer

