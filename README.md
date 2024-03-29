In a Diner 53
=============

A minimal, self-hostable server for [inadyn](https://github.com/troglobit/inadyn)
clients, which updates a hosted zone in [Amazon Route53](https://aws.amazon.com/route53/).
Originally inspired by [this blog post](https://dixonwille.medium.com/custom-dynamic-dns-with-unifi-dream-machine-pro-b664f7c964).


## What Is This

The `inadyn` tool is used internally by several network gateway appliances to provide
[Dynamic DNS](https://en.wikipedia.org/wiki/Dynamic_DNS) integration, including
Netgear, [DD-WRT](https://wiki.dd-wrt.com/wiki/index.php/Dynamic_DNS),
[UniFi](https://help.ui.com/hc/en-us/articles/9203184738583-UniFi-Gateway-Dynamic-DNS),
and others. This client uses a simple interface based on HTTP GET requests to
notify a DDNS service when the local public IP address has changed.

While there are many hosted services which offer inadyn-compatible endpoints,
they often cost money, represent yet another piece of infrastructure to
manage, and require an extra `CNAME` resolution if you want to map a domain you
control to your dynamic IP. This project offers an alternative self-hosted
solution that integrates with the AWS Route53 service, which may be appealing
if you are already using it to manage your domains.


## Usage

Run the server as a JAR using Java or as a self-contained linux native-image.
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

How this looks in your particular network gateway interface will vary by brand.
Depending on how much flexibility your gateway gives you, you may need to front
this with a proxy that serves conventional HTTPS on port 443.

You can query the server's internal state using the `/dyndns/state` endpoint:

```bash
curl -s http://localhost:8300/dyndns/state | jq
```


## Configuration

The server is configured using environment variables. Use `dynr53 --help` to
see a list of all available options. The Route53 zone identifier is required,
and you'll probably want to set some authentication credentials if the server
is accessible beyond the local host.

| Environment           | Description |
|-----------------------|-------------|
| `DYNR53_ZONE_ID`      | Route53 Hosted Zone identifier to apply updates to.
| `DYNR53_BASIC_AUTH`   | Require clients to present this 'user:pass' using basic auth.
| `DYNR53_HTTP_ADDRESS` | IP address to bind the HTTP server to.
| `DYNR53_HTTP_PORT`    | TCP port to bind the HTTP server to.
| `DYNR53_STATE_DIR`    | Directory for persisting state between runs.
| `DYNR53_TARGETS_FILE` | Path to a file containing tab-separated hostname and address pairs to set as DNS targets.

The AWS credentials are resolved using the
[default provider chain](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html#credentials-default),
so you can put them in any of the standard environment or filesystem locations.


## License

This is free and unencumbered software released into the public domain.
See the UNLICENSE file for more information.
