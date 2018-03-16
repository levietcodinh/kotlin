package org.jetbrains.kotlin.daemon.common.experimental.socketInfrastructure

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.actor
import java.io.Serializable
import java.net.InetSocketAddress

/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */
interface ServerBase

@Suppress("UNCHECKED_CAST")
interface Server<out T : ServerBase> : ServerBase {

    val serverPort: Int

    enum class State {
        WORKING, CLOSED, ERROR, DOWNING
    }

    suspend fun processMessage(msg: AnyMessage<in T>, output: ByteWriteChannelWrapper): State = when (msg) {
        is Server.Message<in T> -> Server.State.WORKING.also { msg.process(this as T, output) }
        is Server.EndConnectionMessage<in T> -> Server.State.CLOSED
        is Server.ServerDownMessage<in T> -> Server.State.DOWNING
        else -> Server.State.ERROR
    }

    suspend fun attachClient(client: Socket): Deferred<State>  = async {
        val (input, output) = client.openIO()
        var finalState = Server.State.WORKING
        loop@
        while (true) {
            val state = processMessage(input.nextObject() as Server.AnyMessage<T>, output)
            when (state) {
                Server.State.WORKING -> continue@loop
                else -> {
                    finalState = state
                    break@loop
                }
            }
        }
        finalState
    }

    interface AnyMessage<ServerType : ServerBase> : Serializable

    interface Message<ServerType : ServerBase> : AnyMessage<ServerType> {
        suspend fun process(server: ServerType, output: ByteWriteChannelWrapper)
    }

    class EndConnectionMessage<ServerType : ServerBase> : AnyMessage<ServerType>

    class ServerDownMessage<ServerType : ServerBase> : AnyMessage<ServerType>

    fun runServer(): Deferred<Unit> {
        Report.log("binding to address($serverPort)", "DefaultServer")
        val serverSocket = aSocket().tcp().bind(InetSocketAddress(serverPort))
        return async {
            serverSocket.use {
                Report.log("accepting clientSocket...", "DefaultServer")
                while (true) {
                    val client = serverSocket.accept()
                    Report.log("client accepted! (${client.remoteAddress})", "DefaultServer")
                    attachClient(client).invokeOnCompletion {
                        when (it) {
                            Server.State.DOWNING -> TODO("DOWN")
                            else -> {
                            }
                        }
                    }
                }
            }
        }
    }

}