package configProperties

import java.util.*
import kotlin.reflect.*

@Serializable(NodeReader::class)
data class Connection(val host: String, val port: Int)

@Serializable(NodeReader::class)
data class Config(val log: Connection, val data: Connection)

/*
log.host = logger.example.com
log.port = 514
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
}

interface NodeReader<T> {
    fun Node.read(): T
}

@InjectSerializer
fun <T> Node.read(name: String, reader: NodeReader<T>) = with(reader) { child(name).read() }

object __ConnectionNodeReader : NodeReader<Connection> {
    override fun Node.read(): Connection {
        val host = readString("host")
        val port = readInt("port")
        return Connection(host, port)
    }
}

object __ConfigNodeReader : NodeReader<Config> {
    override fun Node.read(): Config {
        val log = read("log", __ConnectionNodeReader)
        val data = read("data", __ConnectionNodeReader)
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
    val config = Node(props).read("", __ConfigNodeReader)
    println(config)
}

// ---- aux annotations ---

annotation class Serializable(vararg val serializers: KClass<*>)
annotation class InjectSerializer

