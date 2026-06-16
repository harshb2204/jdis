# Asynchronous Networking
## Supporting Concurrent Clients

Most common way of supporting concurrent client is:
- Each client -> its own thread

**Key Challenge** -> Making code thread-safe
- Threads unnecessarily waits
- Slows execution, increases complexity
For I/O your thread is waiting. Once it receives something it is free to move forward, but because you have a critical section it would be stuck again as only one thread is allowed at a time. 
eg: count ++. All threads are waiting and only one thread is allowed to do count++ at a time. This would slow things down. 

## I/O Multiplexing: Async I/O Programming
Any event loop is just a program that repeatedly does 
```
while (true) {
    wait for events
    handle events
}
```

- **Python AsyncIO**, **JavaScript**, **libevent**, **libuv**, ... any concurrent single threaded app built with this.
Why is it only for I/O why cant it be CPU. Why cant u do count++ in a seperate event loop. Why are u only able to do IO in a seperate event loop.
Is my event loop a seperate process. It cannot be. When you run python [something] and node [something]  you dont see 2 process spin up. You dont see 1 nodejs thread and one event loop thread spin up. If event loop is a seperate thread the language is not single threaded. If it was a seperate thread why cant we schedule normal cpu inst to that. Event loop is neither a seperate thread nor a seperate process. It is just a thin layer that is able to support only I/O. This ability to support multiple I/O has to be provided by the kernel. Kernel needs to impl something so that u get notified when something is about to happen in an I/O. 
- **How to actually implement?** `EPOLL`, `KQueue`, `IOCP`
  - `EPOLL` monitors a lot of file descriptors for new I/O (we would use this to understand asynchronous programming)


![](/diagrams/asyncio.png)

You have a client who is connecting via socket to the server. Socket is a logical entity. In reality the client is connecting to the network card. From this the data needs to go into your kernel buffer. As soon as the packet hits the network card, the network card triggers an interrupt. So your kerner stops doing everything else and reads from the network card. So now the data is placed in the kerner buffer. You might have 100s of multiple process running in your application space. Your cpu has limited cores.(eg 4) So at max concurrently 4 processes can move forward. When your process is getting the cpu to execute itself the data is coped from kernel space to user space. This is how your socket that you are managing in your code gets the data. The asynchronous part is some client connecting to your server sending the data. But until and unless your process gets scheduled it will not get the data. So your application is doing:- If there is any data available in the kernel buffer then i will copy it. This is how I/O happens. This tells us because of this copy step in between there a time where your kernel knows for this process i have some data with me. Because your kernel knows that the data that it received was over this socket for this particular process. Your kernel can tell if there is some I/O ready. This I/O is not with respect to your end client but with respect to the data availability of the process in the kernel space. This is how EPOLL Kqueue work internally. 

## Core Idea

**What we do:**
We will continue doing our work, once a while check if someone is ready for an I/O.
- If yes, do the I/O
- If no, continue

**EPOLL tells us this** (whether someone is ready for an I/O).

*A File Descriptor is a 32-bit integer value that uniquely identifies a file (e.g., Disk, Socket, etc.).*

In UNIX everything is a file, hence they have a **File Descriptor** (e.g., Disk I/O, Network I/O).

- We create a new EPOLLER with `epoll_create1`.
- Hence we ask EPOLL to monitor every single client connection along with the main server socket.
- We register/deregister File Descriptors with `epoll_ctl`
  - ↳ Pipes, FIFO, Sockets, inotify, etc.
- But, how would we know when some I/O is ready?
  - `epoll_wait`
  - ↳ waits for updates on registered FDs
  - This is a blocking call, and it moves forward [when an event occurs].
  The code will not move forward until there are 1 or more file descriptors ready for I/O. 
  We can have an infinite loop where we check if there is any I/O we can do. If ready read that, execute the command return the response and it is done. This way you can support large number of clients without any kind of multithreading, because the system call interface is telling you which I/O is ready. If you have not used epoll and every I/O call is blocking. If you are reading from one socket you cannot read from multiple fd at one time in a single threaded system. Until and unless your client sends you something you will not be able to move forward. But with `epoll_wait` it is telling you on which fd if you make a read/write call you would get something. 