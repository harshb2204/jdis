# How Redis expires the keys

In Redis, we can set expiry to keys

```redis
EXPIRE k 10
```

Expire the key 'k' in '10' seconds

- no burden for manual del
- no memory leaks
- naturally fits in real products

Anyone trying to access the key after 10 seconds would get a 'nil'

## Passive mode

A key is passively expired when some client tries to access it, and the key is found to be expired.

What about keys that are never accessed?

## Active mode

10 times every second
1. test random keys having some EXP set
2. delete keys that are found to be expired
3. if more than 25% keys were expired, repeat

Sample is representative of population. if sample has more than 25% expired key, population would have the same proportion.
