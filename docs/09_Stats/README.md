# Stats

Stats are extremely crucial for any database.
It helps us:
- see how our DB is performing
- automate precautionary actions
- monitoring and alerting

We can see Redis stats through command **INFO**.

---

## The INFO command

Spits out server stats and INFO in a parsable format.
Bulk string that contains Section and `field:value` terminated by `\r\n`.

```
# Keyspace
db0:keys=4 \r\n
db1:keys=17 \r\n

# Server
...
```
