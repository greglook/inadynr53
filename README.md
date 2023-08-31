In A Diner 53
=============

A minimal server for [inadyn](https://github.com/troglobit/inadyn) clients to
call, which updates configured entries in AWS Route53.


## Configuration

The binary is configured using environment variables. Use `dynr53 --help` to
see a list of the available options. The most important one is `DYNR53_ZONE_ID`,
which identifies the Route53 Hosted Zone to manage records in.


## Usage

The server listens for update requests at the path `/dyndns/update` and expects
to be passed a `hostname` and `address` in the query parameters. This
corresponds to an `inadyn` configuration like:

```
custom local@1 {
    ddns-server = dyndns.example.com
    ddns-path = /dyndns/update?hostname=%h&address=%i
    hostname = foo.example.com
}
```

Depending on how much flexibility your network appliance gives you, you may need
to front this with a proxy that serves conventional HTTPS on port 443.
