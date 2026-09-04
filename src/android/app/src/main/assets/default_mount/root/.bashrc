# Interactive bash configuration
# Sourced via the default BASH_ENV set in /etc/profile.d/minis.sh.

# Common quality-of-life aliases.
alias ll='ls -alF'
alias la='ls -A'
alias l='ls -CF'
alias ..='cd ..'
alias ...='cd ../..'

# Colored prompt parity with the PS1 baked in minis.sh.
PS1='\u@minis:\w\$ '
