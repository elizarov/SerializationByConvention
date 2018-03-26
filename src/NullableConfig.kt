package nullableConfig

import java.util.*
import kotlin.reflect.*

@Serializable(NodeReader::class)
data class Connection(val host: String, val port: Int)

@Serializable(NodeReader::class)
data class Config(val log: Connection?, val data: Connection)

/*
data.host = incoming.example.com
data.port = 7011
*/

class Node(private val prop: Properties, private val prefix: String = "") {
    // reading functions
    fun readString(name: String): String = prop.getProperty(key(name))
    fun readInt(name: String): Int = prop.getProperty(key(name)).toInt()
    // auxiliary
    fun child(name: String) = Node(prop, key(name))
    private fun key(name: String) = if (prefix == "") name else "$prefix.$name"

    fun hasChild(name: String): Boolean {
        val prefix = key(name) + "."
        return prop.keys.any { it is String && it.startsWith(prefix) }
    }
}

interface NodeReader<T> {
    fun Node.read(): T
}

@InjectSerializer
fun <T> Node.read(name: String, reader: NodeReader<T>) = with(reader) { child(name).read() }

@InjectSerializer
fun <T> Node.readNullable(name: String, reader: NodeReader<T>): T? =
    if (hasChild(name)) read(name, reader) else null

object __ConnectionNodeReader : NodeReader<Connection> {
    override fun Node.read(): Connection {
        val host = readString("host")
        val port = readInt("port")
        return Connection(host, port)
    }
}

object __ConfigNodeReader : NodeReader<Config> {
    override fun Node.read(): Config {
        val log = readNullable("log", __ConnectionNodeReader)
        val data = read("data", __ConnectionNodeReader)
        return Config(log, data)
    }
}

// --- test code ---

fun main(args: Array<String>) {
    val props = Properties()
    props["data.host"] = "incoming.example.com"
    props["data.port"] = "7011"
    println(Node(props).read("", __ConfigNodeReader))
    props["log.host"] = "logger.example.com"
    props["log.port"] = "514"
    println(Node(props).read("", __ConfigNodeReader))
}

// ---- aux annotations ---

annotation class Serializable(vararg val interfaces: KClass<*>)
annotation class InjectSerializer

