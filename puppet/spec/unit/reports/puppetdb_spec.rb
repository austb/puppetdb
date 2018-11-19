#!/usr/bin/env rspec

require 'spec_helper'
require 'puppet/reports'
require 'net/http'
require 'puppet/network/http_pool'
require 'puppet/util/puppetdb/command_names'
require 'puppet/util/puppetdb/config'
require 'json'

processor = Puppet::Reports.report(:puppetdb)

describe processor do

  subject {
    s = Puppet::Transaction::Report.new("foo").extend(processor)
    s.configuration_version = 123456789
    s.environment = "foo"
    s
  }

  context "#process" do

    let(:http) { mock "http" }
    let(:httpok) { Net::HTTPOK.new('1.1', 200, '') }

    def without_producer_timestamp(json_body)
      parsed = JSON.parse(json_body)
      parsed.delete("producer_timestamp")
      parsed.to_json
    end

    it "should POST the report command as a URL-encoded JSON string" do
      httpok.stubs(:body).returns '{"uuid": "a UUID"}'
      subject.stubs(:run_duration).returns(10)

      expected_body = subject.report_to_hash(Time.now.utc).to_json

      Puppet::Network::HttpPool.expects(:http_instance).returns(http)
      http.expects(:post).with {|path, body, headers|
        expect(path).to include(Puppet::Util::Puppetdb::Command::CommandsUrl)

        # producer_timestamp is generated at submission time, so remove it from
        # the comparison
        expect(without_producer_timestamp(body)).to eq(without_producer_timestamp(expected_body))
      }.returns(httpok)

      subject.process
    end
  end

  context "#report_to_hash" do
    let (:resource) {
      stub("resource",
        { :pathbuilder => ["foo", "bar", "baz"],
          :path => "foo",
          :file => "foo",
          :line => 1,
          :tags => [],
          :provider => "foo",
          :type => "foo",
          :title => "foo",
          :merge_into => nil})
    }

    let (:status) {
        Puppet::Resource::Status.new(resource)
    }

    before :each do
      subject.add_resource_status(status)
    end

    it "should include the transaction uuid or nil" do
      subject.transaction_uuid = 'abc123'
      if defined?(subject.catalog_uuid) then
        subject.catalog_uuid = 'bde432'
      end

      result = subject.report_to_hash(Time.now.utc)
      expect(result["transaction_uuid"]).to eq('abc123')

      # This won't be defined on < Puppet 4.3.3
      if defined?(subject.catalog_uuid) then
        expect(result["catalog_uuid"]).to eq('bde432')
      else
        expect(result["catalog_uuid"]).to eq('abc123')
      end
    end

    it "should include the code_id or nil" do
      if defined?(subject.code_id) then
        subject.code_id = 'bde432'
      end
      result = subject.report_to_hash(Time.now.utc)
      if defined?(subject.code_id) then
        expect(result["code_id"]).to eq('bde432')
      else
        expect(result["code_id"]).to be_nil
      end
    end

    it "should include the job_id or nil" do
      if defined?(subject.job_id) then
        subject.job_id = '1337'
      end
      result = subject.report_to_hash(Time.now.utc)
      if defined?(subject.job_id) then
        expect(result["job_id"]).to eq('1337')
      else
        expect(result["job_id"]).to be_nil
      end
    end

    it "should include the producer or nil" do
      Puppet[:node_name_value] = "foo"
      result = subject.report_to_hash(Time.now.utc)
      expect(result["producer"]).to eq("foo")
    end

    it "should include noop_pending or nil" do
      if defined?(subject.noop_pending) then
        subject.noop_pending = false
      end
      result = subject.report_to_hash(Time.now.utc)
      if defined?(subject.noop_pending) then
        expect(result["noop_pending"]).to be(false)
      else
        expect(result["noop_pending"]).to be_nil
      end
    end

    it "should include corrective_change or nil" do
      if defined?(subject.corrective_change) then
        subject.stubs(:corrective_change).returns(false)
      end
      result = subject.report_to_hash(Time.now.utc)
      if defined?(subject.corrective_change) then
        expect(result["corrective_change"]).to be(false)
      else
        expect(result["corrective_change"]).to be_nil
      end
    end

    it "should include the cached_catalog_status or nil" do
      if defined?(subject.cached_catalog_status) then
        subject.cached_catalog_status = 'not_used'
      end
      result = subject.report_to_hash(Time.now.utc)
      if defined?(subject.cached_catalog_status) then
        expect(result["cached_catalog_status"]).to eq('not_used')
      else
        expect(result["cached_catalog_status"]).to be_nil
      end
    end

    context "noop run" do
      before :all do
        Puppet[:noop] = true
      end

      it "should include truthy noop flag" do
        unless (defined?(subject.noop) && (not subject.noop.nil?)) then
          event = Puppet::Transaction::Event.new
          event.status = "noop"
          status.add_event(event)
        end
        result = subject.report_to_hash(Time.now.utc)
        expect(result["noop"]).to be(true)
      end
    end

    context "enforcement run" do
      before :all do
        Puppet[:noop] = false
      end

      it "should include falsey noop flag" do
        unless (defined?(subject.noop) && (not subject.noop.nil?)) then
          event = Puppet::Transaction::Event.new
          event.status = "success"
          status.add_event(event)
        end
        result = subject.report_to_hash(Time.now.utc)
        expect(result["noop"]).to be(false)
      end
    end

    context "start/end time" do
      before :each do
        subject.add_metric("time", {"total" => 10})
      end

      it "should base run duration off of the 'time'->'total' metric" do
        expect(subject.send(:run_duration)).to eq(10)
      end

      it "should use run_duration to calculate the end_time" do
        result = subject.report_to_hash(Time.now.utc)
        duration = Time.parse(result["end_time"]) - Time.parse(result["start_time"])
        expect(duration).to eq(subject.send(:run_duration))
      end
    end

    context "events" do
      before :each do
        subject.stubs(:run_duration).returns(10)
      end

      context "resource without events" do
        it "should not include the resource" do
          result = subject.report_to_hash(Time.now.utc)
          # the server will populate the report id, so we validate that the
          # client doesn't include one
          expect(result.has_key?("report")).to be_falsey
          expect(result["certname"]).to eq(subject.host)
          expect(result["puppet_version"]).to eq(subject.puppet_version)
          expect(result["report_format"]).to eq(subject.report_format)
          expect(result["configuration_version"]).to eq(subject.configuration_version.to_s)
          expect(result["resources"]).to eq([])
          expect(result["noop"]).to be(false)
        end
      end

      context "resource with events" do
        it "should include the resource" do

          event = Puppet::Transaction::Event.new()
          event.property = "fooprop"
          event.desired_value = "fooval"
          event.previous_value = "oldfooval"
          event.message = "foomessage"
          if defined?(event.corrective_change) then
            event.corrective_change = true
          end
          status.add_event(event)

          result = subject.report_to_hash(Time.now.utc)

          expect(result["resources"].length).to eq(1)
          res = result["resources"][0]
          expect(res["resource_type"]).to eq("Foo")
          expect(res["resource_title"]).to eq("foo")
          expect(res["file"]).to eq("foo")
          expect(res["line"]).to eq(1)
          expect(res["containment_path"]).to eq(["foo", "bar", "baz"])
          expect(res["events"].length).to eq(1)
          if defined?(event.corrective_change) then
            expect(res["corrective_change"]).to be(true)
          else
            expect(res["corrective_change"]).to be_nil
          end

          res_event = res["events"][0]
          expect(res_event["property"]).to eq("fooprop")
          expect(res_event["new_value"]).to eq("fooval")
          expect(res_event["old_value"]).to eq("oldfooval")
          expect(res_event["message"]).to eq("foomessage")
          if defined?(event.corrective_change) then
            expect(res_event["corrective_change"]).to be(true)
          else
            expect(res_event["corrective_change"]).to be_nil
          end
        end
      end

      context "skipped resource status" do
        it "should include the resource" do
          status.skipped = true
          result = subject.report_to_hash(Time.now.utc)

          expect(result["resources"].length).to eq(1)
          resource = result["resources"][0]
          expect(resource["resource_type"]).to eq("Foo")
          expect(resource["resource_title"]).to eq("foo")
          expect(resource["containment_path"]).to eq(["foo", "bar", "baz"])
          expect(resource["events"].length).to eq(0)
        end
      end

      context "failed resource status" do
        before :each do
          status.stubs(:failed).returns(true)
        end

        context "with no events" do
          it "should have no events" do
            result = subject.report_to_hash(Time.now.utc)
            expect(result["resources"].length).to eq(0)
          end
        end

        context "with events" do
          it "should include the actual event" do
            event = Puppet::Transaction::Event.new
            event.property = "barprop"
            event.desired_value = "barval"
            event.previous_value = "oldbarval"
            event.message = "barmessage"
            status.add_event(event)

            result = subject.report_to_hash(Time.now.utc)
            expect(result["resources"].length).to eq(1)
            resource = result["resources"][0]
            expect(resource["resource_type"]).to eq("Foo")
            expect(resource["resource_title"]).to eq("foo")
            expect(resource["file"]).to eq("foo")
            expect(resource["line"]).to eq(1)
            expect(resource["containment_path"]).to eq(["foo", "bar", "baz"])
            expect(resource["events"].length).to eq(1)

            res_event = resource["events"][0]
            expect(res_event["property"]).to eq("barprop")
            expect(res_event["new_value"]).to eq("barval")
            expect(res_event["old_value"]).to eq("oldbarval")
            expect(res_event["message"]).to eq("barmessage")
          end
        end
      end

      context "with unchanged resources turned on" do
        let (:config) {
          Puppet::Util::Puppetdb.config
        }

        before :each do
          config.stubs(:include_unchanged_resources?).returns(true)

          notify_resource =
            stub("notify_resource",
                 { :pathbuilder => ["foo", "bar", "baz"],
                   :path => "foo",
                   :file => "foo",
                   :line => "foo",
                   :tags => [],
                   :provider => "foo",
                   :type => "Notify",
                   :title => "Hello there",
                   :merge_into => nil})
          notify_status = Puppet::Resource::Status.new(notify_resource)
          notify_status.changed = false
          subject.add_resource_status(notify_status)

          event = Puppet::Transaction::Event.new()
          event.property = "fooprop"
          event.desired_value = "fooval"
          event.previous_value = "oldfooval"
          event.message = "foomessage"
          status.add_event(event)
        end

        context "with an unchanged resource" do
          it "should include the actual event" do
            result = subject.report_to_hash(Time.now.utc)
            unchanged_resources = result["resources"].select { |res| res["events"].empty? and ! (res["skipped"])}
            expect(unchanged_resources.length).to eq(1)
            resource = unchanged_resources[0]
            expect(resource["resource_type"]).to eq("Notify")
            expect(resource["resource_title"]).to eq("Hello there")
            expect(resource["file"]).to eq("foo")
            expect(resource["line"]).to eq("foo")
            expect(resource["containment_path"]).to eq(["foo", "bar", "baz"])
            expect(resource["events"].length).to eq(0)
          end
        end

      end

    end
  end
end
