package childOperator

import java.util.*
import kotlin.reflect.*

@Serializable(NodeReader::class)
data class Connection(val host: String?, val port: Int)

@Serializable(NodeReader::class)
data class Config(val log: Connection?, val data: Connection)

/*
data.port = 7011
*/

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

interface NodeReader<T> {
    fun Node.read(): T
}

@InjectSerializer
fun <T> Node.read(reader: NodeReader<T>) = with(reader) { read() }

fun <T> Node.readNullable(reader: NodeReader<T>): T? =
    if (hasNode()) read(reader) else null

object __StringNodeReader : NodeReader<String> {
    override fun Node.read(): String = readString()
}

object __ConnectionNodeReader : NodeReader<Connection> {
    override fun Node.read(): Connection {
        val host = child("host").readNullable(__StringNodeReader)
        val port = child("port").readInt()
        return Connection(host, port)
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
    props["data.port"] = "7011"
    println(Node(props).read(__ConfigNodeReader))
    props["data.host"] = "incoming.example.com"
    println(Node(props).read(__ConfigNodeReader))
    props["log.host"] = "logger.example.com"
    props["log.port"] = "514"
    println(Node(props).read(__ConfigNodeReader))
}

// ---- aux annotations ---

annotation class Serializable(vararg val serializers: KClass<*>)
annotation class InjectSerializer

