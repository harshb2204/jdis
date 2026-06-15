# Talking the Redis language

For a Redis Server to understand what a client wants, the client needs to follow a protocol:

### Redis Serialization Protocol (RESP)

RESP supports common datatypes like: **int**, **string**, and **arrays**, and a way to convey **errors**.

Redis sends commands as an **array of strings**.
- **e.g.:** `PUT K V` is sent as `["PUT", "K", "V"]` (Serialized using RESP)

---

# RESP Description

Redis uses RESP as a **request-response protocol**:
- Client sends request in RESP.
- Server responds back in RESP.

**Key rules:**
- Every data type starts with a **special character**.
- The data ends with **\r\n** (CRLF).

## Simple Strings
- Start with a `+`
- Followed by the string (e.g., `PONG`)
- Followed by a CRLF (`\r\n`)
- **Example:** `+PONG\r\n`
- *Note:* Minimal overhead because it requires n+3 bytes in the response.

## Integers
- Start with a `:`
- Followed by the integer (e.g., `1729`)
- Followed by the CRLF (`\r\n`)
- **Example:** `:1729\r\n`

## Bulk Strings
- Start with a `$`
- Followed by the number of bytes
- Followed by a CRLF (`\r\n`)
- Followed by the actual string
- Followed by the CRLF (`\r\n`)
- **Example:** `$4\r\nPONG\r\n`

### Why do we need Bulk strings if we have Simple strings?
1. **Binary Safety:** Bulk strings are binary safe and can contain any byte (simple strings are not).
2. **Termination:** Simple strings cannot contain `\r\n` (CRLF) as it is also used as a terminator.
3. **Versatility:** Bulk strings can store any binary data, even things like a PNG image.

### Some special strings
- **Empty string:** `$0\r\n\r\n`
- **Null Value:** `$-1\r\n` (A length of -1 is a special value indicating a lack of data).

---

## Arrays
- Start with a `*`
- Followed by the **number of elements**
- Followed by a **CRLF** (`\r\n`)
- Followed by the **RESP encoded elements**

**Example:** `["a", 200, "cat"]`
```text
*3\r\n
$1\r\na\r\n
:200\r\n
$3\r\ncat\r\n
```

**Additional notes:**
- **Null arrays:** `*-1\r\n`
- **Empty arrays:** `*0\r\n`
- **Note:** Nested arrays are also supported.

## Errors
- Error messages start with a `-`
- Followed by the message (e.g., `Key not found`)
- Followed by a CRLF (`\r\n`)
- **Example:** `-Key not found \r\n`

---

## Key highlights
1. **Human-readable:** RESP is easy for humans to read and debug.
2. **Simple:** Its simplicity leads to fewer bugs in implementations.
3. **Performant:** Designed for high-speed processing.
4. **Prefixed lengths:** RESP uses prefixed lengths, allowing us to know exactly how many bytes to read and process (e.g., `$5\r\nhello\r\n`).
   - *Note:* This is true even for arrays.
