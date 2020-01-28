require 'puppet/util/puppetdb'
require 'puppet/util/puppetdb/command_names'

Puppet::Face.define(:node, '0.0.1') do

  CommandDeleteNode = Puppet::Util::Puppetdb::CommandNames::CommandDeleteNode

  action :delete do
    summary "Delete a set of nodes in PuppetDB"
    arguments "<node> [<node> ...]"
    description <<-DESC
      This will issue '#{CommandDeleteNode}' commands to the PuppetDB server for
      each node specified. The server is found by looking in
      $confdir/puppetdb.conf. If any command submissions fail, the process will
    be aborted.
    DESC

    when_invoked do |*args|

      _opts = args.pop
      raise ArgumentError, "Please provide at least one node for deletion" if args.empty?

      Puppet::Node.indirection.terminus_class = :puppetdb
      Puppet::Node.indirection.cache_class = nil

      args.inject({}) do |results,node|
        results.merge node => Puppet::Node.indirection.delete(node)['uuid']
      end
    end

    when_rendering(:console) do |value|
      value.map do |node,uuid|
        "Submitted '#{CommandDeleteNode}' for #{node} with UUID #{uuid}"
      end.join("\n")
    end
  end
end
