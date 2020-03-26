---
title: "Setting up SSL for PostgreSQL"
layout: default
canonical: "/puppetdb/latest/postgres_ssl.html"
---

## Talking to PostgreSQL using SSL/TLS

This guide will help you configure SSL/TLS-secured connectivity between PuppetDB and PostgreSQL.

When configuring SSL, you need to decide whether you will use:

1. A self-signed certificate on the PuppetDB server (for example, the Puppet CA)
2. A publicly signed certificate on the PuppetDB server

Both methodologies are valid, but while self-signed certificates are far more common in the real world, this type of configuration must be set up with care.

Before beginning, take a look at PostgreSQL's [secure TCP/IP connections with SSL documentation](http://www.postgresql.org/docs/current/static/ssl-tcp.html), which explains in detail how to configure SSL on the server side.

*Note:* Our guide focuses on server-based SSL. Client certificate support is not documented at this time.

### Using Puppet's certificates for SSL

If you don't have a signed Puppet certificate on your PostgreSQL server, see the [ssl documentaion](https://jdbc.postgresql.org/documentation/head/ssl-client.html) for
alternate SSL connection options.

Using Puppet certificates to secure your PostgreSQL server has the following benefits:

* Because you are using PuppetDB, we can presume that you are using Puppet on each server. This means you can reuse the local Puppet agent certificate for PostgreSQL.
* Because your local Puppet agent's certificate must be signed for Puppet to work, you likely have an established workflow for getting these signed.
* We also recommend this methodology for securing the HTTPS interface for PuppetDB.

Make sure you place your signed certificate and private key in the locations specified by the `ssl_cert_file` and `ssl_key_file` locations, and that you change the `ssl` setting to `on` in your `postgresql.conf`. Don't forget to give the correct permissions for each file (such as `chmod 0600 file`). Otherwise, PostgreSQL will reject the key and cert files.

After this is done, modify the JDBC URL in the database configuration section for PuppetDB. For example:

    [database]
    classname = org.postgresql.Driver
    subprotocol = postgresql
    subname = //<HOST>:<PORT>/<DATABASE>?ssl=true&sslfactory=org.postgresql.ssl.LibPQFactory&sslmode=verify-full&sslrootcert=/etc/puppetlabs/puppetdb/ssl/ca.pem
    username = <USERNAME>
    password = <PASSWORD>

Restart PuppetDB and monitor your logs for errors. If all goes well your connection should now be SSL.
If you are on a PuppetDB version before 6.8.0, the `sslfactory` will be `org.postgresql.ssl.jdbc4.LibPQFactory`

### Setting up SSL with a publicly signed certificate on the PuppetDB server

First, obtain your signed certificate using the process required by your commercial Certificate Authority. 

> **Note:** If you don't want to pay for individual certificates for each server in your enterprise, you can probably get away with using wildcards in the subject or CN for your certificate. Note, however, that while at the moment DNS resolution based on CN isn't tested using the default SSLSocketFactory, we do not know if this will change going forward, and therefore if wildcard support will be included.

Follow the documentation for [secure TCP/IP connections with SSL](http://www.postgresql.org/docs/current/static/ssl-tcp.html), which explains in detail how to configure SSL on the server. Make sure you place your signed certificate and private key in the locations specified by the `ssl_cert_file` and `ssl_key_file` locations, and that you change the `ssl` setting to `on` in your `postgresql.conf`. Don't forget to give the correct permissions for each file (such as `chmod 0600 file`). Otherwise, PostgreSQL will reject the key and cert files.

Because the JDBC PostgreSQL driver utilizes the Java's system KeyStore, and because the system KeyStore usually contains all public CAs, there should be no trust issues with the client configuration. Simply modify the JDBC URL as provided in the database configuration section for PuppetDB.

For example:

    [database]
    classname = org.postgresql.Driver
    subprotocol = postgresql
    subname = //<HOST>:<PORT>/<DATABASE>?ssl=true&sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory
    username = <USERNAME>
    password = <PASSWORD>

Restart PuppetDB and monitor your logs for errors. Your connection should now be SSL.

#### Using a custom Java keystore

If your CA cert is not in Java's default keystore, it may be necessary to create your own. Create a
TrustStore containing your CA certificate. If you have been using PuppetDB for a while, you might
already have such a file in `/etc/puppetdb/ssl/truststore.jks`. If not, the quickest way to create
this file is:

    $ sudo keytool -import -alias "My CA" -file /path/to/ca_crt.pem -keystore /etc/puppetdb/ssl/truststore.jks

Tell Java to use this TrustStore instead of the system's default by specifying values for the
properties for `trustStore` and `trustStorePassword`. These properties can be applied by modifying
your service settings for PuppetDB and appending the required settings to the JAVA_ARGS variable.
In Red Hat, the path to this file is `/etc/sysconfig/puppetdb`. In Debian, use
`/etc/default/puppetdb`. For example:

    # Modify this if you'd like to change the memory allocation, enable JMX, etc.
    JAVA_ARGS="-Xmx192m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/puppetlabs/puppetdb/puppetdb-oom.hprof -Djavax.net.ssl.trustStore=/etc/puppetlabs/puppetdb/ssl/truststore.jks -Djavax.net.ssl.trustStorePassword=<PASSWORD>"

*Note:* Replace `<PASSWORD>` with the password you used to create the KeyStore, or the one found in
`/etc/puppetdb/ssl/puppetdb_keystore_pw.txt`.

After this is complete, modify the database JDBC connection URL in your PuppetDB configuration as
the documentation describes above for publicly signed certificates.

### Disabling SSL verification

**Warning: This is not recommended.** SSL connections offer a higher level of security. Disabling SSL verification effectively removes the ability for the SSL client to detect man-in-the-middle attacks.

However, if you wish to disable SSL verification, you can do so by simply modifying your JDBC URL in the database configuration section of PuppetDB as follows:

{% comment %}This code block broke Jekyll for some reason. I'm using a pre-formatted version of it instead of chasing down the problem. -NF{% endcomment %}

<pre><code>[database]
classname = org.postgresql.Driver
subprotocol = postgresql
subname = //&lt;HOST&gt;:&lt;PORT&gt;/&lt;DATABASE&gt;?ssl=true&amp;sslfactory=org.postgresql.ssl.NonValidatingFactory
username = &lt;USERNAME&gt;
password = &lt;PASSWORD&gt;
</code></pre>

Restart PuppetDB and monitor your logs for errors. Your connection should now be SSL, with validation disabled.
