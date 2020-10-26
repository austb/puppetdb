unless (test_config[:skip_presuite_provisioning])

  step "Update CA cerificates" do
    os = test_config[:os_families][master.name]
    case os
    when :redhat
      on master, "yum install -y ca-certificates"
    when :fedora
      on master, "yum install -y ca-certificates"
    when :debian
      on master, "apt-get install -y ca-certificates"
    end
  end

  if is_rhel8
    # work around for testing on rhel8 and the repos on the image not finding the pg packages it needs
    step "Install PostgreSQL manually" do
      on master, "dnf install -y https://download.postgresql.org/pub/repos/yum/reporpms/EL-8-x86_64/pgdg-redhat-repo-latest.noarch.rpm"
      on master, "dnf -qy module disable postgresql"
    end
  end
end
