#!/usr/bin/env rspec
# encoding: utf-8

require 'spec_helper'

require 'puppet/util/puppetdb/char_encoding'

describe Puppet::Util::Puppetdb::CharEncoding do
  describe "#utf8_string" do
    describe "on ruby >= 1.9" do
      it "should convert from ascii without a warning" do
        Puppet.expects(:warning).never

        str = "any ascii string".force_encoding('us-ascii')
        expect(subject.utf8_string(str, nil)).to eq(str)
      end

      it "should convert from latin-1 without a warning" do
        Puppet.expects(:warning).never

        str = "a latin-1 string Ö".force_encoding('ASCII-8BIT')
        expect(subject.utf8_string(str, nil)).to eq("a latin-1 string Ö")
      end

      # UndefinedConversionError
      it "should replace undefined characters and warn when converting from binary" do
        Puppet.expects(:warning).with {|msg| msg =~ /Error with command ignoring invalid UTF-8 byte sequences/}

        str = "an invalid binary string \xff".force_encoding('binary')
        # \ufffd == unicode replacement character
        expect(subject.utf8_string(str, "Error with command")).to eq("an invalid binary string \ufffd")
      end

      # InvalidByteSequenceError
      it "should replace undefined characters and warn if the string is invalid UTF-8" do
        Puppet.expects(:warning).with {|msg| msg =~ /Error with command ignoring invalid UTF-8 byte sequences/}

        str = "an invalid utf-8 string \xff".force_encoding('utf-8')
        expect(subject.utf8_string(str, "Error with command")).to eq("an invalid utf-8 string \ufffd")
      end

      it "should leave the string alone if it's valid UTF-8" do
        Puppet.expects(:warning).never

        str = "a valid utf-8 string".force_encoding('utf-8')
        expect(subject.utf8_string(str, nil)).to eq(str)
      end

      it "should leave the string alone if it's valid UTF-8 with non-ascii characters" do
        Puppet.expects(:warning).never

        str = "a valid utf-8 string Ö"
        expect(subject.utf8_string(str.dup.force_encoding('ASCII-8BIT'), nil)).to eq(str)
      end

      describe "Debug log testing of bad data" do
        let!(:existing_log_level){ Puppet[:log_level]}

        before :each do
          Puppet[:log_level] = "debug"
        end

        after :each do
          Puppet[:log_level] = "notice"
        end

        it "should emit a warning and debug messages when bad characters are found" do
          Puppet[:log_level] = "debug"
          Puppet.expects(:warning).with {|msg| msg =~ /Error encoding a 'replace facts' command for host 'foo.com' ignoring invalid/}
          Puppet.expects(:debug).with do |msg|
            msg =~ /Error encoding a 'replace facts' command for host 'foo.com'/ &&
            msg =~ /'some valid string' followed by 1 invalid\/undefined bytes then ''/
          end

          # This will create a UTF-8 string literal, then switch to ASCII-8Bit when the bad
          # bytes are concated on below
          str = "some valid string" << [192].pack('c*')
          expect(subject.utf8_string(str, "Error encoding a 'replace facts' command for host 'foo.com'")).to eq("some valid string\ufffd")
        end
      end

      it "should emit a warning and no debug messages" do
        Puppet.expects(:warning).with {|msg| msg =~ /Error on replace catalog ignoring invalid UTF-8 byte sequences/}
        Puppet.expects(:debug).never
        str = "some valid string" << [192].pack('c*')
        expect(subject.utf8_string(str, "Error on replace catalog")).to eq("some valid string\ufffd")
      end
    end
  end

  describe "on ruby >= 1.9" do
    it "finds first change of character" do
      expect(described_class.first_invalid_char_range("123\ufffd\ufffd\ufffd\ufffd123123123\ufffd\ufffd")).to eq(Range.new(3,6))
      expect(described_class.first_invalid_char_range("1234567")).to be_nil
      expect(described_class.first_invalid_char_range("123\ufffd4567")).to eq(Range.new(3,3))
    end

    it "gives error context around each bad character" do
      expect(described_class.error_char_context("abc\ufffddef", 3..3)).to eq("'abc' followed by 1 invalid/undefined bytes then 'def'")

      expect(described_class.error_char_context("abc\ufffd\ufffd\ufffd\ufffddef", 3..6)).to eq("'abc' followed by 4 invalid/undefined bytes then 'def'")

      expect(described_class.error_char_context("abc\ufffddef\ufffdg", 3..3)).to eq("'abc' followed by 1 invalid/undefined bytes then 'def'")
    end
  end
end
